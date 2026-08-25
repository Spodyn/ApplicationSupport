from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from scripts.validate_environment import (
    DEFAULT_CONTRACT,
    EnvironmentValidationError,
    load_contract,
    parse_env_file,
    validate_environment,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def valid_staging_environment(core_directory: Path, integration_directory: Path) -> dict[str, str]:
    return {
        "SPRING_PROFILES_ACTIVE": "staging",
        "USI_PUBLIC_BASE_URL": "https://staging.example.invalid",
        "USI_SLACK_CALLBACK_URL": "https://staging.example.invalid/api/v1/provider-callbacks/slack",
        "USI_TEAMS_CALLBACK_URL": "https://staging.example.invalid/api/v1/provider-callbacks/teams",
        "USI_TELEGRAM_CALLBACK_URL": "https://staging.example.invalid/api/v1/provider-callbacks/telegram",
        "USI_CORS_ALLOWED_ORIGINS": "",
        "USI_DATABASE_URL": "jdbc:postgresql://db.internal:5432/usi",
        "USI_RABBITMQ_HOST": "rabbitmq.internal",
        "USI_RABBITMQ_PORT": "5672",
        "USI_RABBITMQ_VHOST": "usi",
        "USI_OBJECT_STORAGE_ENDPOINT": "https://objects.internal",
        "USI_OBJECT_STORAGE_REGION": "eu-central-1",
        "USI_ATTACHMENTS_BUCKET": "usi-attachments-staging",
        "USI_EXPORTS_BUCKET": "usi-exports-staging",
        "USI_CORE_SECRETS_DIRECTORY": f"{core_directory}/",
        "USI_INTEGRATION_SECRETS_DIRECTORY": f"{integration_directory}/",
    }


def write_core_secret_files(
    core_directory: Path, contract: Mapping[str, Any]
) -> None:
    core_directory.mkdir(parents=True, exist_ok=True)
    for secret_name in contract["coreSecretFiles"]:
        (core_directory / str(secret_name)).write_text(
            "runtime-injected-test-value", encoding="utf-8"
        )


class EnvironmentContractTest(unittest.TestCase):
    contract = load_contract(DEFAULT_CONTRACT)

    def test_checked_in_examples_are_valid_and_separated(self) -> None:
        api_environment = parse_env_file(REPOSITORY_ROOT / ".env.example")
        web_environment = parse_env_file(REPOSITORY_ROOT / "apps/web/.env.example")

        validate_environment("api", api_environment, contract=self.contract)
        validate_environment("web", web_environment, contract=self.contract)

        self.assertFalse(any(name.startswith("NEXT_PUBLIC_") for name in api_environment))
        self.assertTrue(web_environment)
        self.assertTrue(all(name.startswith("NEXT_PUBLIC_") for name in web_environment))

    def test_all_four_explicit_profiles_have_a_valid_configuration_shape(self) -> None:
        local_environment = parse_env_file(REPOSITORY_ROOT / ".env.example")
        validate_environment("api", local_environment, contract=self.contract)

        test_environment = {
            "SPRING_PROFILES_ACTIVE": "test",
            "USI_PUBLIC_BASE_URL": "http://localhost:3000",
            "USI_SLACK_CALLBACK_URL": "https://test.example.invalid/api/v1/provider-callbacks/slack",
            "USI_TEAMS_CALLBACK_URL": "https://test.example.invalid/api/v1/provider-callbacks/teams",
            "USI_TELEGRAM_CALLBACK_URL": "https://test.example.invalid/api/v1/provider-callbacks/telegram",
        }
        validate_environment("api", test_environment, contract=self.contract)

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            for profile in ("staging", "production"):
                with self.subTest(profile=profile):
                    environment = valid_staging_environment(
                        root / f"{profile}-core", root / f"{profile}-integrations"
                    )
                    environment["SPRING_PROFILES_ACTIVE"] = profile
                    environment["USI_PUBLIC_BASE_URL"] = (
                        f"https://{profile}.example.invalid"
                    )
                    validate_environment("api", environment, contract=self.contract)

    def test_missing_required_value_fails_without_echoing_other_values(self) -> None:
        environment = parse_env_file(REPOSITORY_ROOT / ".env.example")
        required_names = {
            name
            for name, specification in self.contract["backendVariables"].items()
            if "local" in specification["requiredProfiles"]
        }

        for missing_name in required_names:
            with self.subTest(missing_name=missing_name):
                incomplete_environment = dict(environment)
                omitted_value = incomplete_environment.pop(missing_name)
                with self.assertRaises(EnvironmentValidationError) as raised:
                    validate_environment(
                        "api", incomplete_environment, contract=self.contract
                    )
                message = str(raised.exception)
                self.assertIn(missing_name, message)
                if (
                    omitted_value
                    and self.contract["backendVariables"][missing_name][
                        "classification"
                    ]
                    == "local-secret"
                ):
                    self.assertNotIn(omitted_value, message)

    def test_urls_reject_credentials_cross_origin_browser_values_and_plain_http_callbacks(self) -> None:
        api_environment = parse_env_file(REPOSITORY_ROOT / ".env.example")
        api_environment["USI_DATABASE_URL"] = (
            "jdbc:postgresql://operator:credential@127.0.0.1:5432/usi"
        )
        api_environment["USI_SLACK_CALLBACK_URL"] = (
            "http://localhost:8080/api/v1/provider-callbacks/slack"
        )

        with self.assertRaises(EnvironmentValidationError) as raised:
            validate_environment("api", api_environment, contract=self.contract)

        self.assertIn("USI_DATABASE_URL must not contain credentials", str(raised.exception))
        self.assertIn("USI_SLACK_CALLBACK_URL must use HTTPS", str(raised.exception))

        with self.assertRaises(EnvironmentValidationError):
            validate_environment(
                "web",
                {
                    "NEXT_PUBLIC_API_BASE_URL": "https://api.example.invalid/api/v1",
                    "NEXT_PUBLIC_WS_BASE_URL": "/ws",
                },
                contract=self.contract,
            )

    def test_production_rejects_every_unreviewed_spring_override_channel(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            environment = valid_staging_environment(
                root / "core", root / "integrations"
            )
            environment["SPRING_PROFILES_ACTIVE"] = "production"
            generated_marker = "generated-override-material"
            override_channels = {
                "SPRING_DATASOURCE_URL": (
                    f"jdbc:postgresql://operator:{generated_marker}@db.internal/usi"
                ),
                "SPRING_APPLICATION_JSON": (
                    '{"spring.datasource.password":"'
                    + generated_marker
                    + '"}'
                ),
                "SPRING_CONFIG_ADDITIONAL_LOCATION": (
                    f"file:/tmp/{generated_marker}/"
                ),
                "spring.application.json": (
                    '{"spring.rabbitmq.password":"'
                    + generated_marker
                    + '"}'
                ),
                ".".join(("usi", "object-storage", "secret-key")): generated_marker,
                "JAVA_TOOL_OPTIONS": (
                    f"-Xmx512m -Dspring.datasource.password={generated_marker}"
                ),
            }

            for name, value in override_channels.items():
                with self.subTest(name=name):
                    candidate = dict(environment)
                    candidate[name] = value
                    with self.assertRaises(EnvironmentValidationError) as raised:
                        validate_environment(
                            "api", candidate, contract=self.contract
                        )
                    message = str(raised.exception)
                    self.assertIn(name, message)
                    self.assertNotIn(generated_marker, message)

    def test_production_object_storage_endpoint_rejects_query_parameters(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            environment = valid_staging_environment(
                root / "core", root / "integrations"
            )
            environment["SPRING_PROFILES_ACTIVE"] = "production"
            generated_marker = "generated-credential-material"
            environment["USI_OBJECT_STORAGE_ENDPOINT"] = (
                f"https://objects.internal?access_key={generated_marker}"
            )

            with self.assertRaises(EnvironmentValidationError) as raised:
                validate_environment("api", environment, contract=self.contract)

            message = str(raised.exception)
            self.assertIn("USI_OBJECT_STORAGE_ENDPOINT", message)
            self.assertIn("query string", message)
            self.assertNotIn(generated_marker, message)

    def test_callback_prefix_requires_a_complete_path_segment(self) -> None:
        environment = parse_env_file(REPOSITORY_ROOT / ".env.example")
        environment["USI_SLACK_CALLBACK_URL"] = (
            "https://callback.example.invalid/api/v10-not-v1"
        )

        with self.assertRaises(EnvironmentValidationError) as raised:
            validate_environment("api", environment, contract=self.contract)

        self.assertIn(
            "USI_SLACK_CALLBACK_URL callback path must stay under /api/v1",
            str(raised.exception),
        )

    def test_staging_uses_non_optional_config_tree_and_checks_every_secret_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            core_directory = root / "core"
            integration_directory = root / "integrations"
            core_directory.mkdir()
            integration_directory.mkdir()
            environment = valid_staging_environment(
                core_directory, integration_directory
            )

            write_core_secret_files(core_directory, self.contract)

            validate_environment(
                "api",
                environment,
                contract=self.contract,
                check_secret_files=True,
            )

            missing_name = self.contract["coreSecretFiles"][0]
            (core_directory / missing_name).unlink()
            with self.assertRaises(EnvironmentValidationError) as raised:
                validate_environment(
                    "api",
                    environment,
                    contract=self.contract,
                    check_secret_files=True,
                )
            self.assertIn(missing_name, str(raised.exception))
            self.assertNotIn("runtime-injected-test-value", str(raised.exception))

    def test_runtime_secret_directories_must_not_overlap(self) -> None:
        relationships = (
            "same",
            "integration-inside-core",
            "core-inside-integration",
        )
        for relationship in relationships:
            with (
                self.subTest(relationship=relationship),
                tempfile.TemporaryDirectory() as temporary_directory,
            ):
                root = Path(temporary_directory)
                if relationship == "same":
                    core_directory = root / "shared"
                    integration_directory = core_directory
                elif relationship == "integration-inside-core":
                    core_directory = root / "core"
                    integration_directory = core_directory / "integrations"
                else:
                    integration_directory = root / "integrations"
                    core_directory = integration_directory / "core"

                integration_directory.mkdir(parents=True, exist_ok=True)
                write_core_secret_files(core_directory, self.contract)
                environment = valid_staging_environment(
                    core_directory, integration_directory
                )

                with self.assertRaises(EnvironmentValidationError) as raised:
                    validate_environment(
                        "api",
                        environment,
                        contract=self.contract,
                        check_secret_files=True,
                    )

                message = str(raised.exception)
                self.assertIn(
                    "core and integration secret directories must be distinct, non-overlapping boundaries",
                    message,
                )
                self.assertNotIn(str(root), message)

    def test_runtime_secret_directory_aliases_must_not_resolve_together(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            core_directory = root / "core"
            integration_directory = root / "integration-alias"
            write_core_secret_files(core_directory, self.contract)
            integration_directory.symlink_to(core_directory, target_is_directory=True)
            environment = valid_staging_environment(
                core_directory, integration_directory
            )

            with self.assertRaises(EnvironmentValidationError) as raised:
                validate_environment(
                    "api",
                    environment,
                    contract=self.contract,
                    check_secret_files=True,
                )

            message = str(raised.exception)
            self.assertIn(
                "core and integration secret directories must be distinct, non-overlapping boundaries",
                message,
            )
            self.assertNotIn(str(root), message)

    def test_imported_core_secret_tree_rejects_unapproved_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            core_directory = root / "core"
            integration_directory = root / "integrations"
            integration_directory.mkdir()
            write_core_secret_files(core_directory, self.contract)
            extra_entry = core_directory / "provider.secret"
            extra_entry.write_text("runtime-injected-test-value", encoding="utf-8")
            environment = valid_staging_environment(
                core_directory, integration_directory
            )

            with self.assertRaises(EnvironmentValidationError) as raised:
                validate_environment(
                    "api",
                    environment,
                    contract=self.contract,
                    check_secret_files=True,
                )

            message = str(raised.exception)
            self.assertIn("outside the approved core secret allowlist", message)
            self.assertNotIn(extra_entry.name, message)

    def test_staging_rejects_plaintext_core_and_provider_secret_environment_names(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            environment = valid_staging_environment(root / "core", root / "integrations")
            runtime_name = "SPRING_DATASOURCE_PASSWORD"
            provider_name = "USI_SLACK_SIGNING_SECRET"
            environment[runtime_name] = "not-printed"
            environment[provider_name] = "also-not-printed"

            with self.assertRaises(EnvironmentValidationError) as raised:
                validate_environment("api", environment, contract=self.contract)

            message = str(raised.exception)
            self.assertIn(runtime_name, message)
            self.assertIn(provider_name, message)
            self.assertNotIn("not-printed", message)

    def test_profile_resources_keep_runtime_secrets_out_of_application_properties(self) -> None:
        resources = REPOSITORY_ROOT / "apps/api/src/main/resources"
        local = (resources / "application-local.properties").read_text(encoding="utf-8")
        staging = (resources / "application-staging.properties").read_text(encoding="utf-8")
        production = (resources / "application-production.properties").read_text(encoding="utf-8")

        self.assertIn("${USI_DATABASE_PASSWORD}", local)
        for runtime_profile in (staging, production):
            self.assertIn("spring.config.import=configtree:${USI_CORE_SECRETS_DIRECTORY}", runtime_profile)
            self.assertNotIn("optional:configtree", runtime_profile)
            self.assertNotIn("USI_DATABASE_PASSWORD", runtime_profile)
            self.assertNotIn("USI_SLACK_SIGNING_SECRET", runtime_profile)
            self.assertIn("usi.integration-secrets.backend=configtree", runtime_profile)

        persistence = self.contract["integrationSecretPersistence"]
        self.assertEqual("secret_ref", persistence["storedField"])
        self.assertFalse(persistence["resolvedValueStoredInDatabase"])
        self.assertFalse(persistence["bulkImportedIntoApplicationEnvironment"])

    def test_profile_resources_and_placeholders_match_the_machine_contract(self) -> None:
        resources = REPOSITORY_ROOT / "apps/api/src/main/resources"
        expected_profile_files = {
            f"application-{profile}.properties" for profile in self.contract["profiles"]
        }
        actual_profile_files = {
            path.name
            for path in resources.glob("application-*.properties")
        }
        self.assertEqual(expected_profile_files, actual_profile_files)

        known_names = set(self.contract["backendVariables"])
        placeholder_pattern = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::[^}]*)?}")
        for resource in resources.glob("application*.properties"):
            content = resource.read_text(encoding="utf-8")
            self.assertLessEqual(
                set(placeholder_pattern.findall(content)),
                known_names,
                resource.name,
            )

        for profile in self.contract["profiles"]:
            profile_content = (
                resources / f"application-{profile}.properties"
            ).read_text(encoding="utf-8")
            self.assertIn(
                f"spring.config.activate.on-profile={profile}", profile_content
            )

    def test_gitignore_ignores_all_env_overrides_but_not_examples(self) -> None:
        ignored_paths = (
            ".env",
            ".env.local",
            "apps/web/.env.production",
            "infra/.env.test.local",
        )
        visible_examples = (
            ".env.example",
            "apps/web/.env.example",
            "infra/.env.example",
        )

        for relative_path in ignored_paths:
            result = subprocess.run(
                ("git", "check-ignore", "--quiet", "--no-index", relative_path),
                cwd=REPOSITORY_ROOT,
                check=False,
            )
            self.assertEqual(0, result.returncode, relative_path)
        for relative_path in visible_examples:
            result = subprocess.run(
                ("git", "check-ignore", "--quiet", "--no-index", relative_path),
                cwd=REPOSITORY_ROOT,
                check=False,
            )
            self.assertEqual(1, result.returncode, relative_path)


if __name__ == "__main__":
    unittest.main()
