from __future__ import annotations

import json
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CANONICAL_TELEGRAM_CALLBACK_PATH = "/api/v1/provider-callbacks/telegram"


class TelegramDevelopmentContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.guide = (REPOSITORY_ROOT / "docs" / "TELEGRAM_DEVELOPMENT.md").read_text(
            encoding="utf-8"
        )

    def test_developer_facing_callback_examples_use_the_canonical_route(self) -> None:
        env_example = (REPOSITORY_ROOT / ".env.example").read_text(encoding="utf-8")
        local_development = (REPOSITORY_ROOT / "docs" / "LOCAL_DEVELOPMENT.md").read_text(
            encoding="utf-8"
        )

        for content in (env_example, local_development, self.guide):
            self.assertIn(CANONICAL_TELEGRAM_CALLBACK_PATH, content)

    def test_guide_limits_updates_and_conversations_to_the_frozen_v1_scope(self) -> None:
        for update in ("message", "edited_message", "my_chat_member"):
            with self.subTest(update=update):
                self.assertIn(f"`{update}`", self.guide)
        self.assertIn("Broadcast channels are out of scope", " ".join(self.guide.split()))
        self.assertIn("forum topic maps to one active Case generation", self.guide)
        self.assertIn("chat without topics maps to one active Case generation", self.guide)

    def test_guide_keeps_bot_and_webhook_secrets_outside_process_environment(self) -> None:
        environment_contract = json.loads(
            (REPOSITORY_ROOT / "config" / "environment-contract.json").read_text(
                encoding="utf-8"
            )
        )
        forbidden = environment_contract["forbiddenProviderSecretEnvironmentNames"]

        self.assertIn("Integration.secret_ref", self.guide)
        self.assertIn("USI_INTEGRATION_SECRETS_DIRECTORY", self.guide)
        self.assertIn("telegram-bot-token", self.guide)
        self.assertIn("telegram-webhook-secret-token", self.guide)
        self.assertIn("X-Telegram-Bot-Api-Secret-Token", self.guide)
        self.assertTrue(any(name.startswith("USI_TELEGRAM_") for name in forbidden))
        for forbidden_name in forbidden:
            self.assertNotIn(f"{forbidden_name}=", self.guide)


if __name__ == "__main__":
    unittest.main()
