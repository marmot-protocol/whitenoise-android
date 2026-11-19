from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from scripts.translation_worklist import scan


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
