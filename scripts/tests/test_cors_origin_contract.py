from __future__ import annotations

import unittest
from pathlib import Path

from scripts.validate_environment import (
    EnvironmentValidationError,
    load_contract,
    parse_env_file,
    validate_environment,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class CorsOriginContractTest(unittest.TestCase):
    contract = load_contract()

    def local_environment(self) -> dict[str, str]:
        return parse_env_file(REPOSITORY_ROOT / ".env.example")

    def staging_environment(self, cors_origins: str) -> dict[str, str]:
        return {
            "SPRING_PROFILES_ACTIVE": "staging",
            "USI_PUBLIC_BASE_URL": "https://staging.example.invalid",
            "USI_SLACK_CALLBACK_URL": "https://staging.example.invalid/api/v1/providers/slack/events",
            "USI_TEAMS_CALLBACK_URL": "https://staging.example.invalid/api/v1/provider-callbacks/teams",
            "USI_TELEGRAM_CALLBACK_URL": "https://staging.example.invalid/api/v1/provider-callbacks/telegram",
            "USI_CORS_ALLOWED_ORIGINS": cors_origins,
            "USI_DATABASE_URL": "jdbc:postgresql://db.internal:5432/usi",
            "USI_RABBITMQ_HOST": "rabbitmq.internal",
            "USI_RABBITMQ_PORT": "5672",
            "USI_RABBITMQ_VHOST": "usi",
            "USI_OBJECT_STORAGE_ENDPOINT": "https://objects.internal",
            "USI_OBJECT_STORAGE_REGION": "eu-central-1",
            "USI_ATTACHMENTS_BUCKET": "usi-attachments-staging",
            "USI_EXPORTS_BUCKET": "usi-exports-staging",
            "USI_CORE_SECRETS_DIRECTORY": "/tmp/usi-staging-core-secrets/",
            "USI_INTEGRATION_SECRETS_DIRECTORY": "/tmp/usi-staging-integration-secrets/",
        }

    def test_same_origin_default_keeps_cors_disabled(self) -> None:
        environment = self.local_environment()
        self.assertEqual("", environment["USI_CORS_ALLOWED_ORIGINS"])
        validate_environment("api", environment, contract=self.contract)

    def test_explicit_local_origins_are_allowed_only_as_exact_origins(self) -> None:
        environment = self.local_environment()
        environment["USI_CORS_ALLOWED_ORIGINS"] = (
            "http://localhost:3100,http://127.0.0.1:3200"
        )
        validate_environment("api", environment, contract=self.contract)

    def test_wildcard_cors_is_rejected(self) -> None:
        for value in ("*", "https://*.example.invalid"):
            with self.subTest(value=value):
                environment = self.local_environment()
                environment["USI_CORS_ALLOWED_ORIGINS"] = value
                with self.assertRaises(EnvironmentValidationError) as raised:
                    validate_environment("api", environment, contract=self.contract)
                self.assertIn(
                    "USI_CORS_ALLOWED_ORIGINS must be empty or an explicit comma-separated origin list",
                    str(raised.exception),
                )

    def test_origin_paths_queries_and_credentials_are_rejected(self) -> None:
        invalid_values = (
            "http://localhost:3100/api",
            "http://localhost:3100?client=web",
            "http://user:password@localhost:3100",
        )
        for value in invalid_values:
            with self.subTest(value=value):
                environment = self.local_environment()
                environment["USI_CORS_ALLOWED_ORIGINS"] = value
                with self.assertRaises(EnvironmentValidationError):
                    validate_environment("api", environment, contract=self.contract)

    def test_staging_cors_origins_must_use_https(self) -> None:
        validate_environment(
            "api",
            self.staging_environment("https://console.example.invalid"),
            contract=self.contract,
        )

        with self.assertRaises(EnvironmentValidationError) as raised:
            validate_environment(
                "api",
                self.staging_environment("http://console.example.invalid"),
                contract=self.contract,
            )

        self.assertIn(
            "USI_CORS_ALLOWED_ORIGINS entries must use HTTPS in the staging profile",
            str(raised.exception),
        )


if __name__ == "__main__":
    unittest.main()
