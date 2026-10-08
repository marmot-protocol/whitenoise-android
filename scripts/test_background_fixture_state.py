"""Pin restoration against personal-user state, absent permissions and ambiguous overrides."""

import unittest

from scripts.background_fixture_state import RECEIVER, delivery_state, protected_users


class DeliveryStateTest(unittest.TestCase):
    """Exercises package-user boundaries rather than assuming permission or receiver defaults."""

    def fixture(self, grant="true", section="", entries=""):
        """Keep personal state opposite to the disposable profile's state."""
        return (
            "    User 0: installed=true\n"
            "      runtime permissions:\n"
            "        android.permission.POST_NOTIFICATIONS: granted=false, flags=[]\n"
            "      disabledComponents:\n"
            f"        {RECEIVER}\n"
            "    User 11: installed=true\n"
            "      runtime permissions:\n"
            f"        android.permission.POST_NOTIFICATIONS: granted={grant}, flags=[]\n"
            f"{section}{entries}"
            "    User 12: installed=true\n"
        )

    def test_personal_override_does_not_leak(self):
        self.assertEqual(delivery_state(self.fixture(), 11), {"permission_granted": True, "receiver_action": "default-state"})

    def test_disabled_receiver_and_revoked_permission_preserved(self):
        text = self.fixture("false", "      disabledComponents:\n", f"        {RECEIVER}\n")
        self.assertEqual(delivery_state(text, 11), {"permission_granted": False, "receiver_action": "disable"})

    def test_explicit_enable_differs_from_default(self):
        text = self.fixture(section="      enabledComponents:\n", entries=f"        {RECEIVER}\n")
        self.assertEqual(delivery_state(text, 11)["receiver_action"], "enable")

    def test_unrelated_disabled_component_is_not_ingress(self):
        text = self.fixture(section="      disabledComponents:\n", entries="        unrelated.receiver\n")
        self.assertEqual(delivery_state(text, 11)["receiver_action"], "default-state")

    def test_absent_permission_fails_closed(self):
        text = self.fixture().replace("POST_NOTIFICATIONS: granted=true", "OTHER_PERMISSION: granted=true")
        with self.assertRaises(ValueError):
            delivery_state(text, 11)

    def test_duplicate_user_block_fails_closed(self):
        with self.assertRaises(ValueError):
            delivery_state(self.fixture() + "    User 11: installed=true\n", 11)

    def test_conflicting_overrides_fail_closed(self):
        entries = f"        {RECEIVER}\n      disabledComponents:\n        {RECEIVER}\n"
        with self.assertRaises(ValueError):
            delivery_state(self.fixture(section="      enabledComponents:\n", entries=entries), 11)

    def test_protected_users_preserve_all_original_override_states(self):
        text = "\n".join(f"    User {user}: installed=true enabled={user}" for user in range(5))
        text += "\n    User 11: installed=true enabled=0\n    User 12: installed=false enabled=0"
        self.assertEqual(
            protected_users(text, 11),
            [{"user": user, "action": action} for user, action in enumerate(
                ("default-state", "enable", "disable", "disable-user", "disable-until-used")
            )],
        )

    def test_missing_protected_override_is_not_assumed_enabled(self):
        with self.assertRaises(ValueError):
            protected_users("    User 0: installed=true\n    User 11: installed=true enabled=0", 11)

    def test_unknown_app_override_fails_closed(self):
        with self.assertRaises(ValueError):
            protected_users("    User 0: installed=true enabled=9\n    User 11: installed=true enabled=0", 11)

    def test_absent_fixture_user_fails_closed(self):
        with self.assertRaises(ValueError):
            protected_users("    User 0: installed=true enabled=0", 11)

    def test_art_diagnostic_user_headings_do_not_shadow_package_state(self):
        text = self.fixture() + "    User 0:\n      dex info\n    User 11:\n      dex info\n"
        self.assertTrue(delivery_state(text, 11)["permission_granted"])
        text = "    User 0: installed=true enabled=0\n    User 11: installed=true enabled=0\n    User 0:\n    User 11:\n"
        self.assertEqual(protected_users(text, 11), [{"user": 0, "action": "default-state"}])


if __name__ == "__main__":
    unittest.main()
