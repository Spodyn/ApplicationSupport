#!/usr/bin/env python3
"""Render and validate the development Teams app manifest without handling secrets."""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TEMPLATE = ROOT / "docs" / "poc" / "teams-rsc" / "manifest.template.json"
EXPECTED_SCHEMA = "https://developer.microsoft.com/json-schemas/teams/v1.21/MicrosoftTeams.schema.json"
EXPECTED_SCOPES = {"team", "groupChat"}
EXPECTED_RSC = {
    ("Application", "ChannelMessage.Read.Group"),
    ("Application", "ChatMessage.Read.Chat"),
}
FORBIDDEN_PERMISSIONS = {"ChannelMessage.Read.All", "Chat.Read.All", "Chat.ReadWrite.All"}
PLACEHOLDERS = {"__TEAMS_APP_ID__", "__TEAMS_BOT_CLIENT_ID__"}


def guid(value: str, label: str) -> str:
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError) as exc:
        raise ValueError(f"{label} must be a GUID") from exc
    return str(parsed)


def https_url(value: str, label: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError(f"{label} must be an HTTPS URL without credentials")
    if parsed.query or parsed.fragment:
        raise ValueError(f"{label} must not contain query or fragment")
    return value.rstrip("/")


def https_origin(value: str, label: str) -> str:
    value = https_url(value, label)
    parsed = urlparse(value)
    if parsed.path not in ("", "/") or parsed.params:
        raise ValueError(f"{label} must be an HTTPS origin without a path")
    return value.rstrip("/")


def replace(value, replacements: dict[str, str]):
    if isinstance(value, str):
        for key, replacement in replacements.items():
            value = value.replace(key, replacement)
        return value
    if isinstance(value, list):
        return [replace(item, replacements) for item in value]
    if isinstance(value, dict):
        return {key: replace(item, replacements) for key, item in value.items()}
    return value


def validate_manifest(manifest: dict) -> None:
    if manifest.get("$schema") != EXPECTED_SCHEMA or manifest.get("manifestVersion") != "1.21":
        raise ValueError("Teams manifest must use the reviewed v1.21 schema")

    developer = manifest.get("developer") or {}
    if not developer.get("name") or len(developer["name"]) > 32:
        raise ValueError("developer.name must satisfy the Teams manifest 32-character limit")
    short_name = (manifest.get("name") or {}).get("short", "")
    if not short_name or len(short_name) > 30:
        raise ValueError("name.short must satisfy the Teams manifest 30-character limit")
    description = manifest.get("description") or {}
    if not description.get("short") or len(description["short"]) > 80:
        raise ValueError("description.short must satisfy the Teams manifest 80-character limit")
    if not description.get("full") or len(description["full"]) > 4000:
        raise ValueError("description.full must satisfy the Teams manifest 4000-character limit")

    serialized = json.dumps(manifest, sort_keys=True)
    if any(marker in serialized for marker in PLACEHOLDERS):
        raise ValueError("rendered manifest still contains an unresolved identifier placeholder")
    lowered = serialized.lower()
    for secret_word in ("clientsecret", "client_secret", "privatekey", "access_token", "refresh_token"):
        if secret_word in lowered:
            raise ValueError("manifest must not contain credential fields")

    app_id = guid(manifest.get("id", ""), "manifest id")
    bots = manifest.get("bots")
    if not isinstance(bots, list) or len(bots) != 1:
        raise ValueError("manifest must contain exactly one bot")
    bot = bots[0]
    bot_id = guid(bot.get("botId", ""), "botId")
    if set(bot.get("scopes", [])) != EXPECTED_SCOPES:
        raise ValueError("bot scopes must be exactly team and groupChat")
    if "personal" in bot.get("scopes", []) or "copilot" in bot.get("scopes", []):
        raise ValueError("personal/Copilot scopes are outside the frozen v1 contract")

    web_info = manifest.get("webApplicationInfo") or {}
    if guid(web_info.get("id", ""), "webApplicationInfo.id") != bot_id:
        raise ValueError("standalone development bot must use the bot Entra client id in webApplicationInfo.id")
    if web_info.get("resource") != f"api://botid-{bot_id}":
        raise ValueError("webApplicationInfo.resource must use api://botid-<bot-client-id>")

    permissions = (
        manifest.get("authorization", {})
        .get("permissions", {})
        .get("resourceSpecific", [])
    )
    actual = {(item.get("type"), item.get("name")) for item in permissions}
    if actual != EXPECTED_RSC:
        raise ValueError("resourceSpecific permissions must match the frozen Teams RSC contract")
    if any(item.get("name") in FORBIDDEN_PERMISSIONS for item in permissions):
        raise ValueError("tenant-wide message-read permissions are forbidden")

    for field in ("websiteUrl", "privacyUrl", "termsOfUseUrl"):
        https_url(developer.get(field, ""), f"developer.{field}")
    if app_id == bot_id:
        # Supported, but make the relationship explicit rather than accidentally depending on it.
        pass


def render(template_path: Path, app_id: str, bot_client_id: str, public_base_url: str) -> dict:
    app_id = guid(app_id, "Teams app id")
    bot_client_id = guid(bot_client_id, "Teams bot/Entra client id")
    public_base_url = https_origin(public_base_url, "public base URL")
    manifest = json.loads(template_path.read_text(encoding="utf-8"))
    manifest = replace(
        manifest,
        {
            "__TEAMS_APP_ID__": app_id,
            "__TEAMS_BOT_CLIENT_ID__": bot_client_id,
            "https://example.invalid": public_base_url,
        },
    )
    validate_manifest(manifest)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--teams-app-id", required=True)
    parser.add_argument("--bot-client-id", required=True)
    parser.add_argument("--public-base-url", required=True)
    parser.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    try:
        manifest = render(args.template, args.teams_app_id, args.bot_client_id, args.public_base_url)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"Teams manifest render failed: {exc}", file=sys.stderr)
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Rendered schema-valid USI Teams development manifest: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
