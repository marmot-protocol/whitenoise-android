"""Behavioral release validation checks; fixtures contain synthetic values only."""
import unittest
from scripts.check_product_analytics_config import read_config, validate


class ProductAnalyticsConfigTest(unittest.TestCase):
    def setUp(self):
        """Create a complete synthetic release configuration without operator credentials."""
        self.values = dict(EVENTS_ENDPOINT="https://aptabase.example/api/v0/events", APP_KEY="A-SH-test-123", OPERATOR="white_noise", RETENTION="Scheduled deletion after 180 days.")

    def test_resolved_java_configuration(self):
        """Validate generated Java literals so release checks inspect the actual packaged values."""
        import json
        source = "\n".join(f"public static final String WHITENOISE_PRODUCT_{key} = {json.dumps(value)};" for key, value in self.values.items())
        self.assertEqual(self.values, read_config(source))
        self.assertEqual([], validate(read_config(source)))

    def test_every_value_required(self):
        """Reject blank disclosure or destination fields in the optional product release check."""
        for field in self.values:
            with self.subTest(field=field):
                self.assertIn(field, validate(self.values | {field: " "}))

    def test_bad_destinations(self):
        """Reject insecure, malformed, and credential-bearing URLs before packaging them."""
        for endpoint in ("http://aptabase.example/api/v0/events", "https://aptabase.example", "https://user:pass@aptabase.example/api/v0/events", "https://aptabase.example:bad/api/v0/events", "https://aptabase.example/api/v0/events?key=secret", "https://aptabase.example/api/v0/events#fragment"):
            with self.subTest(endpoint=endpoint):
                self.assertIn("EVENTS_ENDPOINT", validate(self.values | {"EVENTS_ENDPOINT": endpoint}))

    def test_other_tokens_are_not_aptabase_keys(self):
        """Keep OTLP bearer tokens and incomplete app keys out of Aptabase configuration."""
        for key in ("Bearer example", "", "A-SH-", "A-SH-with whitespace"):
            self.assertIn("APP_KEY", validate(self.values | {"APP_KEY": key}))
