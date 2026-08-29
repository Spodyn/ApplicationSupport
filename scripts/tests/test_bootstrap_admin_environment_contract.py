from __future__ import annotations

import unittest

from scripts.validate_environment import (
    DEFAULT_CONTRACT,
    EnvironmentValidationError,
    load_contract,
    validate_environment,
)


class BootstrapAdminEnvironmentContractTest(unittest.TestCase):
    contract = load_contract(DEFAULT_CONTRACT)

    @staticmethod
    def base_test_environment() -> dict[str, str]:
        return {
            "SPRING_PROFILES_ACTIVE": "test",
            "USI_PUBLIC_BASE_URL": "http://localhost:3000",
            "USI_SLACK_CALLBACK_URL": "https://test.example.invalid/api/v1/providers/slack/events",
            "USI_TEAMS_CALLBACK_URL": "https://test.example.invalid/api/v1/provider-callbacks/teams",
            "USI_TELEGRAM_CALLBACK_URL": "https://test.example.invalid/api/v1/provider-callbacks/telegram",
        }

    def test_reviewed_bootstrap_locator_variables_are_allowed(self) -> None:
        environment = self.base_test_environment()
        environment.update(
            {
                "USI_BOOTSTRAP_ADMIN_ENABLED": "true",
                "USI_BOOTSTRAP_ADMIN_EMAIL": "admin@example.invalid",
                "USI_BOOTSTRAP_ADMIN_DISPLAY_NAME": "Bootstrap Administrator",
                "USI_BOOTSTRAP_ADMIN_PASSWORD_FILE": "/run/secrets/usi-bootstrap-admin-password",
            }
        )

        validate_environment("api", environment, contract=self.contract)

    def test_plaintext_bootstrap_password_environment_variable_is_rejected_without_value_echo(self) -> None:
        environment = self.base_test_environment()
        generated_marker = "-".join(("must", "never", "be", "environment"))
        environment["USI_BOOTSTRAP_ADMIN_PASSWORD"] = generated_marker

        with self.assertRaises(EnvironmentValidationError) as raised:
            validate_environment("api", environment, contract=self.contract)

        message = str(raised.exception)
        self.assertIn("USI_BOOTSTRAP_ADMIN_PASSWORD", message)
        self.assertNotIn(generated_marker, message)


if __name__ == "__main__":
    unittest.main()
