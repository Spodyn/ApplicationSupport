#!/usr/bin/env python3
"""Validate USI environment input without evaluating or printing secret values."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import parse_qs, urlsplit


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = REPOSITORY_ROOT / "config/environment-contract.json"
ENVIRONMENT_NAME = re.compile(r"[A-Z][A-Z0-9_]*\Z")
HOSTNAME = re.compile(
    r"(?:localhost|(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*)\Z"
)
BUCKET_NAME = re.compile(r"[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:@/-]{0,254}\Z")
BOT_USERNAME = re.compile(r"[A-Za-z][A-Za-z0-9_]{4,63}\Z")


class EnvironmentValidationError(ValueError):
    """Raised with configuration names only; values are deliberately omitted."""

    def __init__(self, errors: Sequence[str]):
        self.errors = tuple(errors)
        super().__init__("; ".join(self.errors))


def load_contract(path: Path = DEFAULT_CONTRACT) -> dict[str, Any]:
    with path.open(encoding="utf-8") as contract_file:
        contract = json.load(contract_file)
    if contract.get("version") != 1:
        raise ValueError("Unsupported environment contract version")
    return contract


def parse_env_file(path: Path) -> dict[str, str]:
    """Parse the intentionally small KEY=VALUE subset used by USI examples."""

    values: dict[str, str] = {}
    errors: list[str] = []
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            errors.append(f"line {line_number}: shell export syntax is not supported")
            continue
        name, separator, raw_value = line.partition("=")
        if not separator or not ENVIRONMENT_NAME.fullmatch(name):
            errors.append(f"line {line_number}: expected an uppercase KEY=VALUE entry")
            continue
        if name in values:
            errors.append(f"line {line_number}: duplicate variable {name}")
            continue
        value = raw_value.strip()
        if value.startswith(('"', "'")) or value.endswith(('"', "'")):
            if len(value) < 2 or value[0] != value[-1]:
                errors.append(f"line {line_number}: unmatched quote for {name}")
                continue
            value = value[1:-1]
        values[name] = value

    if errors:
        raise EnvironmentValidationError(errors)
    return values


def _validate_same_origin_path(
    name: str, value: str, specification: Mapping[str, Any]
) -> str | None:
    if not value.startswith("/") or value.startswith("//"):
        return f"{name} must be a same-origin absolute path"
    if any(character.isspace() for character in value) or any(
        marker in value for marker in ("?", "#", "\\", "%")
    ):
        return f"{name} must not contain whitespace, query, fragment, or backslash"
    if any(segment in {".", ".."} for segment in PurePosixPath(value).parts):
        return f"{name} must not contain path traversal segments"
    exact_value = specification.get("exactValue")
    if exact_value and value != exact_value:
        return f"{name} must equal the reviewed same-origin path {exact_value}"
    return None


def _is_valid_hostname(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        return "*" not in value and HOSTNAME.fullmatch(value) is not None


def _split_web_url(name: str, value: str) -> tuple[Any | None, str | None]:
    if any(character.isspace() for character in value) or "\\" in value:
        return None, f"{name} contains invalid URL characters"
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None, f"{name} must be an absolute HTTP(S) URL"
    try:
        parsed.port
    except ValueError:
        return None, f"{name} contains an invalid port"
    if not _is_valid_hostname(parsed.hostname):
        return None, f"{name} contains an invalid hostname"
    if parsed.username is not None or parsed.password is not None:
        return None, f"{name} must not contain credentials"
    if parsed.fragment:
        return None, f"{name} must not contain a fragment"
    return parsed, None


def _validate_url(
    name: str,
    value: str,
    variable_type: str,
    specification: Mapping[str, Any],
    profile: str,
) -> str | None:
    parsed, error = _split_web_url(name, value)
    if error:
        return error
    assert parsed is not None
    if variable_type == "https-url" and parsed.scheme != "https":
        return f"{name} must use HTTPS"
    if profile in specification.get("httpsProfiles", []) and parsed.scheme != "https":
        return f"{name} must use HTTPS in the {profile} profile"
    if variable_type == "origin" and (
        parsed.path not in {"", "/"} or parsed.query
    ):
        return f"{name} must be an origin without path or query"
    if variable_type == "https-url":
        if parsed.query:
            return f"{name} must not put data in the callback query string"
        if "%" in parsed.path or any(
            segment in {".", ".."} for segment in PurePosixPath(parsed.path).parts
        ):
            return f"{name} callback path must not contain encoded or traversal segments"
        path_prefix = specification.get("pathPrefix")
        if path_prefix and not parsed.path.startswith(path_prefix):
            return f"{name} callback path must stay under {path_prefix}"
    return None


def _validate_jdbc_url(name: str, value: str) -> str | None:
    prefix = "jdbc:"
    if not value.startswith(prefix):
        return f"{name} must be a PostgreSQL JDBC URL"
    parsed = urlsplit(value[len(prefix) :])
    if parsed.scheme != "postgresql" or not parsed.hostname or parsed.path in {"", "/"}:
        return f"{name} must include a PostgreSQL host and database"
    try:
        parsed.port
    except ValueError:
        return f"{name} contains an invalid port"
    if not _is_valid_hostname(parsed.hostname):
        return f"{name} contains an invalid hostname"
    if parsed.username is not None or parsed.password is not None:
        return f"{name} must not contain credentials"
    if parsed.fragment:
        return f"{name} must not contain a fragment"
    query_names = {
        query_name.lower()
        for query_name in parse_qs(parsed.query, keep_blank_values=True)
    }
    if query_names.intersection({"user", "username", "password", "token", "secret"}):
        return f"{name} must not carry credentials in query parameters"
    return None


def _validate_directory(name: str, value: str) -> str | None:
    candidate = Path(value)
    if not candidate.is_absolute() or ".." in candidate.parts:
        return f"{name} must be an absolute directory without traversal"
    if not value.endswith("/"):
        return f"{name} must end with / for config-tree compatibility"
    return None


def _validate_origin_list(name: str, value: str, profile: str) -> str | None:
    if not value:
        return None
    origins = [origin.strip() for origin in value.split(",")]
    if not all(origins) or "*" in value:
        return f"{name} must be empty or an explicit comma-separated origin list"
    for origin in origins:
        parsed, error = _split_web_url(name, origin)
        if error:
            return error
        assert parsed is not None
        if parsed.path not in {"", "/"} or parsed.query:
            return f"{name} entries must be origins without paths or queries"
        if profile in {"staging", "production"} and parsed.scheme != "https":
            return f"{name} entries must use HTTPS in the {profile} profile"
    return None


def _validate_value(
    name: str,
    value: str,
    specification: Mapping[str, Any],
    profile: str,
    profiles: set[str],
) -> str | None:
    variable_type = specification["type"]
    if variable_type == "profile":
        if value not in profiles:
            return f"{name} must select exactly one supported profile"
        return None
    if variable_type == "same-origin-path":
        return _validate_same_origin_path(name, value, specification)
    if variable_type in {"origin", "web-url", "https-url"}:
        return _validate_url(name, value, variable_type, specification, profile)
    if variable_type == "jdbc-postgresql-url":
        return _validate_jdbc_url(name, value)
    if variable_type == "hostname":
        if not HOSTNAME.fullmatch(value) or "://" in value:
            return f"{name} must be a hostname without scheme or path"
        return None
    if variable_type == "port":
        try:
            port = int(value)
        except ValueError:
            return f"{name} must be an integer port"
        if not 1 <= port <= 65535:
            return f"{name} must be between 1 and 65535"
        return None
    if variable_type == "bucket-name":
        if not BUCKET_NAME.fullmatch(value) or ".." in value:
            return f"{name} must be a valid lower-case S3 bucket name"
        return None
    if variable_type == "absolute-directory":
        return _validate_directory(name, value)
    if variable_type == "origin-list":
        return _validate_origin_list(name, value, profile)
    if variable_type == "non-empty":
        return None if value else f"{name} must not be empty"
    if variable_type == "optional-identifier":
        if value and not IDENTIFIER.fullmatch(value):
            return f"{name} contains unsupported identifier characters"
        return None
    if variable_type == "optional-bot-username":
        if value and not BOT_USERNAME.fullmatch(value):
            return f"{name} must be a provider bot username without @"
        return None
    return f"{name} has unsupported contract type {variable_type}"


def _validate_secret_files(values: Mapping[str, str], contract: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    core_root_value = values.get("USI_CORE_SECRETS_DIRECTORY", "")
    integration_root_value = values.get("USI_INTEGRATION_SECRETS_DIRECTORY", "")
    if core_root_value:
        core_root = Path(core_root_value)
        if not core_root.is_dir():
            errors.append("USI_CORE_SECRETS_DIRECTORY must exist at startup")
        else:
            resolved_root = core_root.resolve()
            for secret_name in contract["coreSecretFiles"]:
                secret_file = core_root / secret_name
                try:
                    resolved_secret = secret_file.resolve(strict=True)
                    resolved_secret.relative_to(resolved_root)
                except (FileNotFoundError, ValueError):
                    errors.append(f"required core secret file is missing: {secret_name}")
                    continue
                if not resolved_secret.is_file() or resolved_secret.stat().st_size == 0:
                    errors.append(f"required core secret file is empty: {secret_name}")
    if integration_root_value and not Path(integration_root_value).is_dir():
        errors.append("USI_INTEGRATION_SECRETS_DIRECTORY must exist at startup")
    return errors


def validate_environment(
    component: str,
    values: Mapping[str, str],
    *,
    contract: Mapping[str, Any] | None = None,
    check_secret_files: bool = False,
) -> None:
    """Validate one component and raise a value-redacting aggregate error."""

    effective_contract = contract or load_contract()
    profiles = set(effective_contract["profiles"])
    errors: list[str] = []

    if component == "web":
        specifications = effective_contract["publicVariables"]
        unknown_public = sorted(
            name
            for name in values
            if name.startswith("NEXT_PUBLIC_") and name not in specifications
        )
        errors.extend(
            f"unreviewed browser-public variable is forbidden: {name}"
            for name in unknown_public
        )
        for name, specification in specifications.items():
            value = values.get(name, "")
            if specification.get("required") and not value:
                errors.append(f"missing required browser-public variable: {name}")
                continue
            if value:
                error = _validate_value(
                    name, value, specification, "web", profiles
                )
                if error:
                    errors.append(error)
    elif component == "api":
        specifications = effective_contract["backendVariables"]
        profile = values.get("SPRING_PROFILES_ACTIVE", "")
        if profile not in profiles:
            errors.append(
                "SPRING_PROFILES_ACTIVE must select exactly one of "
                + ", ".join(effective_contract["profiles"])
            )

        errors.extend(
            f"browser-public configuration must stay in apps/web: {name}"
            for name in sorted(values)
            if name.startswith("NEXT_PUBLIC_")
        )
        errors.extend(
            f"unreviewed USI environment variable is forbidden: {name}"
            for name in sorted(values)
            if name.startswith("USI_") and name not in specifications
        )

        forbidden_provider_names = set(
            effective_contract["forbiddenProviderSecretEnvironmentNames"]
        )
        errors.extend(
            f"provider secret must be resolved by secret_ref, not environment: {name}"
            for name in sorted(forbidden_provider_names.intersection(values))
        )

        if profile in effective_contract["runtimeSecretProfiles"]:
            forbidden_runtime_names = set(
                effective_contract["forbiddenRuntimeSecretEnvironmentNames"]
            )
            errors.extend(
                f"runtime secret must come from the config tree, not environment: {name}"
                for name in sorted(forbidden_runtime_names.intersection(values))
            )

        for name, specification in specifications.items():
            value_is_present = name in values
            value = values.get(name, "")
            required = profile in specification.get("requiredProfiles", [])
            if required and not value:
                errors.append(f"missing required API variable for {profile or 'unknown'}: {name}")
                continue
            allowed_profiles = specification.get("allowedProfiles")
            if value_is_present and allowed_profiles and profile not in allowed_profiles:
                errors.append(f"{name} is forbidden in the {profile or 'unknown'} profile")
                continue
            if value:
                error = _validate_value(
                    name, value, specification, profile, profiles
                )
                if error:
                    errors.append(error)

        if check_secret_files:
            if profile not in effective_contract["runtimeSecretProfiles"]:
                errors.append(
                    "secret-file checks are valid only for staging or production"
                )
            else:
                errors.extend(_validate_secret_files(values, effective_contract))
    else:
        raise ValueError(f"Unsupported component: {component}")

    if errors:
        raise EnvironmentValidationError(errors)


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate USI API or browser environment configuration."
    )
    parser.add_argument("component", choices=("api", "web"))
    parser.add_argument(
        "--env-file",
        type=Path,
        help="Read a KEY=VALUE example/override file instead of the process environment.",
    )
    parser.add_argument(
        "--contract", type=Path, default=DEFAULT_CONTRACT, help=argparse.SUPPRESS
    )
    parser.add_argument(
        "--check-secret-files",
        action="store_true",
        help="For staging/production startup, require every mounted secret file.",
    )
    return parser.parse_args()


def main() -> int:
    arguments = _arguments()
    try:
        values = (
            parse_env_file(arguments.env_file)
            if arguments.env_file
            else dict(os.environ)
        )
        validate_environment(
            arguments.component,
            values,
            contract=load_contract(arguments.contract),
            check_secret_files=arguments.check_secret_files,
        )
    except (EnvironmentValidationError, OSError, ValueError, json.JSONDecodeError) as error:
        print("USI environment validation failed:", file=sys.stderr)
        if isinstance(error, EnvironmentValidationError):
            for message in error.errors:
                print(f"- {message}", file=sys.stderr)
        else:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"USI {arguments.component} environment validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
