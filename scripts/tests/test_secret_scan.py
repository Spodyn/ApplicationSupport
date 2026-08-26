from __future__ import annotations

import unittest

from scripts.secret_scan import (
    FORBIDDEN_PROVIDER_SECRET_NAMES,
    REPOSITORY_ROOT,
    scan_repository,
    scan_text,
)


class SecretScanTest(unittest.TestCase):
    def test_detects_known_token_private_key_and_literal_secret_shapes(self) -> None:
        token = "xo" + "xb-" + "A1b2C3d4E5F6G7H8I9"
        private_key_header = "-----BEGIN " + "PRIVATE KEY-----"
        literal_assignment = '"client_secret": "' + "N7qP2vR8mX4zL9sK" + '"'

        findings = scan_text(
            "generated-fixture",
            "\n".join((token, private_key_header, literal_assignment)),
        )

        self.assertEqual(
            {"slack-token", "private-key", "literal-secret-assignment"},
            {finding.detector for finding in findings},
        )

    def test_detects_sensitive_browser_variable_name_without_recording_value(self) -> None:
        variable_name = "_".join(("NEXT_PUBLIC", "BOT", "TOKEN"))
        findings = scan_text(
            "generated-fixture", f"{variable_name}=" + "value-created-at-test-time"
        )

        self.assertIn("sensitive-next-public-name", {item.detector for item in findings})
        self.assertTrue(all("value-created" not in item.path for item in findings))

    def test_detects_every_contract_forbidden_provider_secret_assignment(self) -> None:
        generated_value = "".join(("N7qP2", "vR8mX", "4zL9s", "K6cD1"))
        telegram_webhook_name = "_".join(
            ("USI", "TELEGRAM", "WEBHOOK", "SECRET", "TOKEN")
        )
        self.assertIn(telegram_webhook_name, FORBIDDEN_PROVIDER_SECRET_NAMES)
        assignment_templates = (
            "{name}={value}",
            "{name}: {value}",
            '"{name}": "{value}"',
        )

        for name in sorted(FORBIDDEN_PROVIDER_SECRET_NAMES):
            for template in assignment_templates:
                with self.subTest(name=name, template=template):
                    findings = scan_text(
                        "generated-fixture",
                        template.format(name=name, value=generated_value),
                    )
                    self.assertIn(
                        "literal-secret-assignment",
                        {finding.detector for finding in findings},
                    )

    def test_forbidden_provider_secret_does_not_accept_placeholder_substrings(self) -> None:
        telegram_webhook_name = "_".join(
            ("USI", "TELEGRAM", "WEBHOOK", "SECRET", "TOKEN")
        )
        value_with_placeholder_substring = "".join(
            ("N7qP2vR8mX4zL9sK", "fake", "6cD1")
        )

        findings = scan_text(
            "generated-fixture",
            f"{telegram_webhook_name}={value_with_placeholder_substring}",
        )

        self.assertIn(
            "literal-secret-assignment",
            {finding.detector for finding in findings},
        )

    def test_forbidden_provider_secret_assignment_fails_closed_when_empty(self) -> None:
        telegram_webhook_name = "_".join(
            ("USI", "TELEGRAM", "WEBHOOK", "SECRET", "TOKEN")
        )

        findings = scan_text("generated-fixture", f"{telegram_webhook_name}=")

        self.assertIn(
            "literal-secret-assignment",
            {finding.detector for finding in findings},
        )

    def test_placeholder_markers_must_form_an_explicit_example_value(self) -> None:
        value_with_embedded_marker = "".join(
            ("N7qP2vR8mX4zL9sK", "fake", "6cD1")
        )

        findings = scan_text(
            "generated-fixture",
            f"webhook_secret_token={value_with_embedded_marker}",
        )

        self.assertIn(
            "literal-secret-assignment",
            {finding.detector for finding in findings},
        )

    def test_detects_general_api_key_and_secret_name_forms(self) -> None:
        generated_value = "".join(("Q8mN4", "zR2pL", "7vX5c", "K9sD3"))

        for name in ("service_api_key", "webhook_secret_token", "clientSecret"):
            with self.subTest(name=name):
                findings = scan_text(
                    "generated-fixture", f"{name}={generated_value}"
                )
                self.assertIn(
                    "literal-secret-assignment",
                    {finding.detector for finding in findings},
                )

    def test_allows_obvious_examples_and_indirect_secret_references(self) -> None:
        text = "\n".join(
            (
                "DATABASE_PASSWORD=database_dev_only_change_me",
                "spring.datasource.password=${USI_DATABASE_PASSWORD}",
                (
                    'POSTGRES_PASSWORD: "'
                    + "$"
                    + '{POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in infra/.env}"'
                ),
                "secret_location=configtree:${USI_CORE_SECRETS_DIRECTORY}",
                "integration.secret_ref=providers/slack/generated-reference-123",
                "USI_CORE_SECRETS_DIRECTORY=/run/secrets/usi-core",
                "resolved_secret = secret_file.resolve(strict=True)",
            )
        )

        self.assertEqual([], scan_text("safe-example", text))

    def test_current_worktree_contains_no_secret_finding(self) -> None:
        self.assertEqual(
            [],
            scan_repository(REPOSITORY_ROOT, include_history=False),
        )


if __name__ == "__main__":
    unittest.main()
