from __future__ import annotations

import unittest

from scripts.secret_scan import REPOSITORY_ROOT, scan_repository, scan_text


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

    def test_allows_obvious_examples_and_indirect_secret_references(self) -> None:
        text = "\n".join(
            (
                "DATABASE_PASSWORD=database_dev_only_change_me",
                "spring.datasource.password=${USI_DATABASE_PASSWORD}",
                "secret_location=configtree:${USI_CORE_SECRETS_DIRECTORY}",
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
