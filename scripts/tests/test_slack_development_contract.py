from __future__ import annotations

import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CANONICAL_SLACK_CALLBACK_PATH = "/api/v1/providers/slack/events"
LEGACY_SLACK_CALLBACK_PATH = "/api/v1/provider-callbacks/slack"


class SlackDevelopmentContractTest(unittest.TestCase):
    def test_developer_facing_callback_examples_use_canonical_e12_route(self) -> None:
        env_example = (REPOSITORY_ROOT / ".env.example").read_text(encoding="utf-8")
        local_development = (REPOSITORY_ROOT / "docs" / "LOCAL_DEVELOPMENT.md").read_text(
            encoding="utf-8"
        )
        guide = (REPOSITORY_ROOT / "docs" / "SLACK_DEVELOPMENT.md").read_text(
            encoding="utf-8"
        )

        for content in (env_example, local_development, guide):
            self.assertIn(CANONICAL_SLACK_CALLBACK_PATH, content)
            self.assertNotIn(LEGACY_SLACK_CALLBACK_PATH, content)

    def test_guide_freezes_minimum_bot_scopes_and_message_events(self) -> None:
        guide = (REPOSITORY_ROOT / "docs" / "SLACK_DEVELOPMENT.md").read_text(
            encoding="utf-8"
        )

        required_scopes = (
            "channels:read",
            "channels:history",
            "groups:read",
            "groups:history",
            "im:read",
            "im:history",
            "mpim:read",
            "mpim:history",
            "chat:write",
        )
        required_events = (
            "message.channels",
            "message.groups",
            "message.im",
            "message.mpim",
        )

        for scope in required_scopes:
            with self.subTest(scope=scope):
                self.assertIn(f"`{scope}`", guide)
        for event in required_events:
            with self.subTest(event=event):
                self.assertIn(f"`{event}`", guide)

    def test_guide_preserves_secret_ref_boundary_and_runtime_signing_secret_layout(self) -> None:
        guide = (REPOSITORY_ROOT / "docs" / "SLACK_DEVELOPMENT.md").read_text(
            encoding="utf-8"
        )

        self.assertIn("Integration.secret_ref", guide)
        self.assertIn("USI_INTEGRATION_SECRETS_DIRECTORY", guide)
        self.assertIn("slack-signing-secret", guide)
        self.assertIn("raw request body before JSON parsing", guide)
        self.assertNotIn("xoxb-", guide)


if __name__ == "__main__":
    unittest.main()
