from pathlib import Path
from unittest import mock
from tempfile import TemporaryDirectory
import contextlib
import io
import unittest

from scripts.translation_worklist import MAIN, main, scan, source_changes, worklist_order


class TranslationWorklistTest(unittest.TestCase):
    def setUp(self):
        temp = TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.write("main/values/strings.xml", '<string name="sign_in">Sign in as %1$s</string>')
        self.write("main/values/help.xml", '<string name="hint">Hold for help</string>')

    def write(self, path, resources):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(f"<resources>{resources}</resources>")

    def scan(self):
        return scan(self.root / "main", self.root / "preview", {"Sign-in": ["sign_in"]})

    def test_partial_locale_can_start_in_preview_in_any_values_file(self):
        self.write("preview/values-fr/account.xml", '<string name="sign_in">Connexion : %1$s</string>')
        _, required, catalogs, errors = self.scan()
        self.assertEqual(errors, [])
        self.assertEqual(required.keys() - catalogs["fr"].keys(), set())
        self.assertNotIn("hint", catalogs["fr"])

    def test_shipped_locale_requires_complete_coverage_even_without_strings_file(self):
        self.write("main/values-fr/account.xml", '<string name="sign_in">Connexion : %1$s</string>')
        self.assertIn("hint: missing from shipped locale", self.scan()[3][0])

    def test_invalid_preview_placeholder_is_rejected(self):
        self.write("preview/values-fr/account.xml", '<string name="sign_in">Connexion : %1$d</string>')
        self.assertIn("sign_in: format arguments differ", self.scan()[3][0])

    def test_preview_cannot_override_a_shipped_locale(self):
        self.write("main/values-fr/account.xml", '<string name="sign_in">Connexion : %1$s</string>')
        self.write("preview/values-fr/account.xml", '<string name="hint">Maintenir pour obtenir de l’aide</string>')
        self.assertTrue(any("already exists in the main resources" in error for error in self.scan()[3]))

    def test_target_plural_categories_can_differ_but_need_other(self):
        self.write(
            "main/values/counts.xml",
            '<plurals name="count"><item quantity="other">%1$d chats</item></plurals>',
        )
        self.write(
            "preview/values-ru/counts.xml",
            '<plurals name="count"><item quantity="few">%1$d чата</item>'
            '<item quantity="other">%1$d чатов</item></plurals>',
        )
        self.assertEqual(self.scan()[3], [])
        self.write(
            "preview/values-ru/counts.xml",
            '<plurals name="count"><item quantity="few">%1$d чата</item></plurals>',
        )
        self.assertIn("including other", self.scan()[3][0])

    def test_argument_reordering_and_literal_percent(self):
        self.write(
            "main/values/details.xml",
            '<string name="details">%1$s: %2$d</string><string name="size">85 percent</string>',
        )
        self.write(
            "preview/values-it/details.xml",
            '<string name="details">%2$d — %1$s</string><string name="size">85% della norma</string>',
        )
        self.assertEqual(self.scan()[3], [])

    def test_shipped_locale_cannot_be_moved_into_preview(self):
        self.write("preview/values-fr/account.xml", '<string name="sign_in">Connexion : %1$s</string>')
        _, _, _, errors = scan(self.root / "main", self.root / "preview", {}, {"values-fr"})
        self.assertIn("shipped locale was removed from the main resources", errors[0])

    def test_stray_percent_in_a_formatted_translation_is_rejected(self):
        for malformed in ("%", "%0%", "%5n"):
            with self.subTest(malformed=malformed):
                self.write(
                    "preview/values-fr/account.xml",
                    f'<string name="sign_in">Connexion : %1$s 85{malformed}</string>',
                )
                self.assertIn("invalid percent sequence", self.scan()[3][0])
        self.write("preview/values-fr/account.xml", '<string name="sign_in">Connexion : %1$s 85%%</string>')
        self.assertEqual(self.scan()[3], [])

    def test_flags_cannot_change_a_source_conversion(self):
        self.write("preview/values-fr/account.xml", '<string name="sign_in">Connexion : %1$+s</string>')
        self.assertIn("format arguments differ", self.scan()[3][0])

    def test_relative_reuse_cannot_hide_invalid_explicit_index(self):
        for index in ("0", "2147483648"):
            with self.subTest(index=index):
                self.write(
                    "preview/values-fr/account.xml",
                    f'<string name="sign_in">Connexion : %1$s %{index}$&lt;s</string>',
                )
                self.assertIn("invalid argument index", self.scan()[3][0])

    def write_counts(self, locale, items):
        self.write(
            "main/values/counts.xml",
            '<plurals name="count"><item quantity="one">One chat</item>'
            '<item quantity="other">%1$d chats</item></plurals>',
        )
        self.write(f"preview/values-{locale}/counts.xml", f'<plurals name="count">{items}</plurals>')

    def test_plural_forms_may_spell_out_or_include_the_count(self):
        self.write_counts(
            "ar",
            '<item quantity="zero">لا محادثات</item><item quantity="two">محادثتان</item>'
            '<item quantity="other">%1$d محادثة</item>',
        )
        self.assertEqual(self.scan()[3], [])
        self.write_counts("ru", '<item quantity="one">%1$d чат</item><item quantity="other">%1$d чатов</item>')
        self.assertEqual(self.scan()[3], [])

    def test_plural_forms_reject_unknown_arguments(self):
        self.write_counts("ru", '<item quantity="one">%1$s чат</item><item quantity="other">%1$d чатов</item>')
        self.assertIn("format arguments differ", self.scan()[3][0])

    def test_malformed_english_plurals_are_reported_with_file_and_key(self):
        for items in ('<item quantity="one">One</item>', '<item quantity="few">A</item><item quantity="other">B</item><item quantity="few">C</item>', '<item quantity="some">A</item><item quantity="other">B</item>'):
            with self.subTest(items=items):
                self.write("main/values/counts.xml", f'<plurals name="count">{items}</plurals>')
                self.write("preview/values-ru/counts.xml", '<plurals name="count"><item quantity="other">%1$d</item></plurals>')
                errors = self.scan()[3]
                self.assertEqual(len(errors), 1)
                self.assertIn("counts.xml: count: expected unique Android plural quantities", errors[0])

    def test_typed_item_alias_is_the_same_resource(self):
        self.write("main/values/alias.xml", '<item name="title" type="string">Title</item>')
        self.write("preview/values-fr/alias.xml", '<string name="title">Titre</string>')
        source, _, _, errors = self.scan()
        self.assertEqual(errors, [])
        base = b'<resources><string name="title">Title</string></resources>'
        paths = [str(MAIN / "values/alias.xml")]
        with mock.patch("scripts.translation_worklist.subprocess.check_output", return_value=base):
            self.assertNotIn("title", source_changes("base", paths, source))
        with mock.patch("scripts.translation_worklist.subprocess.check_output", return_value=base.replace(b"Title", b"Old")):
            self.assertIn("title", source_changes("base", paths, source))

    def test_launch_keys_follow_priority_file_order(self):
        self.write("main/values/strings.xml", '<string name="sign_in">Sign in</string><string name="invite">Invite</string>')
        self.write("main/values/explanation_invite.xml", '<string name="invite_why">Why</string>')
        rules = {"Sign-in": ["sign_in"], "Invites": ["invite", "invite_why"]}
        source, required, _, _ = scan(self.root / "main", self.root / "preview", rules)
        ordered = [name for name, _ in sorted(source.items(), key=lambda pair: worklist_order(pair, required, rules))]
        self.assertEqual(ordered, ["sign_in", "invite", "invite_why", "hint"])

    def write_located_counts(self, items, source_one="One chat in %2$s"):
        self.write(
            "main/values/counts.xml",
            f'<plurals name="count"><item quantity="one">{source_one}</item>'
            '<item quantity="other">%1$d chats in %2$s</item></plurals>',
        )
        self.write("preview/values-ru/counts.xml", f'<plurals name="count">{items}</plurals>')

    def test_plural_forms_must_keep_required_non_count_arguments(self):
        self.write_located_counts('<item quantity="one">Чат в %2$s</item><item quantity="other">%1$d чатов</item>')
        self.assertIn("format arguments differ", self.scan()[3][0])
        self.write_located_counts('<item quantity="one">Чат</item><item quantity="other">Чатов в %2$s</item>')
        self.assertIn("format arguments differ", self.scan()[3][0])

    def test_plural_forms_may_spell_out_count_but_keep_other_arguments(self):
        self.write_located_counts('<item quantity="one">Один чат в %2$s</item><item quantity="other">%1$d чатов в %2$s</item>')
        self.assertEqual(self.scan()[3], [])

    def test_plural_forms_keep_required_argument_when_source_widths_differ(self):
        self.write("main/values/counts.xml", '<plurals name="count"><item quantity="one">One chat in %2$s</item>'
                   '<item quantity="other">%1$d chats in %2$20s</item></plurals>')
        self.write("preview/values-ru/counts.xml", '<plurals name="count"><item quantity="one">Один чат</item>'
                   '<item quantity="other">%1$d чатов</item></plurals>')
        self.assertIn("format arguments differ", self.scan()[3][0])
        self.write("preview/values-ru/counts.xml", '<plurals name="count"><item quantity="one">Один чат в %2$20s</item>'
                   '<item quantity="other">%1$d чатов в %2$s</item></plurals>')
        self.assertEqual(self.scan()[3], [])

    def test_plural_forms_exempt_only_the_count_among_integer_arguments(self):
        self.write("main/values/counts.xml", '<plurals name="count"><item quantity="one">%1$d chat in %2$d rooms</item>'
                   '<item quantity="other">%1$d chats in %2$d rooms</item></plurals>')
        self.write("preview/values-ru/counts.xml", '<plurals name="count"><item quantity="one">Один чат</item>'
                   '<item quantity="other">%1$d чатов</item></plurals>')
        self.assertIn("format arguments differ", self.scan()[3][0])
        self.write("preview/values-ru/counts.xml", '<plurals name="count"><item quantity="one">Один чат в %2$d комнатах</item>'
                   '<item quantity="other">%1$d чатов в %2$d комнатах</item></plurals>')
        self.assertEqual(self.scan()[3], [])

    def test_plural_markup_ignores_wrapped_count_but_not_other_tags(self):
        ns = 'xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2"'
        wrapped = '<xliff:g id="n">%1$d</xliff:g>'
        self.write("main/values/counts.xml", f'<plurals name="count" {ns}><item quantity="one">One chat</item>'
                   f'<item quantity="other">{wrapped} chats</item></plurals>')
        self.write("preview/values-ru/counts.xml", f'<plurals name="count" {ns}><item quantity="one">Один чат</item>'
                   '<item quantity="other">%1$d чатов</item></plurals>')
        self.assertEqual(self.scan()[3], [])
        self.write("main/values/counts.xml", '<plurals name="count"><item quantity="one">One chat</item>'
                   '<item quantity="other">%1$d chats</item></plurals>')
        self.write("preview/values-ru/counts.xml", f'<plurals name="count" {ns}><item quantity="one">{wrapped} чат</item>'
                   f'<item quantity="other">{wrapped} чатов</item></plurals>')
        self.assertEqual(self.scan()[3], [])
        self.write("preview/values-ru/counts.xml", '<plurals name="count"><item quantity="one">Один чат</item>'
                   '<item quantity="other"><b>%1$d</b> чатов</item></plurals>')
        self.assertIn("markup tags differ", self.scan()[3][0])
        foreign = 'xmlns:x="urn:example"'
        self.write("preview/values-ru/counts.xml", f'<plurals name="count" {foreign}><item quantity="one">Один чат</item>'
                   '<item quantity="other"><x:g>%1$d</x:g> чатов</item></plurals>')
        self.assertIn("markup tags differ", self.scan()[3][0])

    def test_default_base_summary_lists_new_key_absent_from_every_locale(self):
        self.write("main/values/new.xml", '<string name="fresh">Fresh</string>')
        self.write("preview/values-fr/account.xml", '<string name="sign_in">Connexion : %1$s</string>')
        (self.root / "docs").mkdir()
        (self.root / "docs/translation-priorities.json").write_text('{"Sign-in": ["sign_in"]}')
        base = b"<resources></resources>"
        out = io.StringIO()
        with mock.patch("scripts.translation_worklist.ROOT", self.root), \
                mock.patch("scripts.translation_worklist.MAIN", Path("main")), \
                mock.patch("scripts.translation_worklist.PREVIEW", Path("preview")), \
                mock.patch("scripts.translation_worklist.base_resources", return_value=["main/values/new.xml"]), \
                mock.patch("scripts.translation_worklist.subprocess.check_output", return_value=base), \
                mock.patch("sys.argv", ["translation_worklist.py", "--base", "origin/master"]), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            main()
        self.assertIn("new.xml/fresh (in: none; needs translation: fr)", out.getvalue())
