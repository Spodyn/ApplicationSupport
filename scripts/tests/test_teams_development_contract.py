import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GUID_APP = "11111111-1111-4111-8111-111111111111"
GUID_BOT = "22222222-2222-4222-8222-222222222222"
TEMPLATE = ROOT / "docs" / "poc" / "teams-rsc" / "manifest.template.json"
GUIDE = ROOT / "docs" / "TEAMS_DEVELOPMENT.md"
ENV_CONTRACT = ROOT / "config" / "environment-contract.json"
RENDERER = ROOT / "scripts" / "render_teams_dev_manifest.py"

spec = importlib.util.spec_from_file_location("render_teams_dev_manifest", RENDERER)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


class TeamsDevelopmentContractTests(unittest.TestCase):
    def test_template_contains_only_reviewed_v1_scopes_and_rsc(self):
        template = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        self.assertEqual(template["$schema"], module.EXPECTED_SCHEMA)
        self.assertEqual(template["manifestVersion"], "1.21")
        self.assertEqual(template["id"], "__TEAMS_APP_ID__")
        self.assertNotIn("packageName", template)
        self.assertLessEqual(len(template["developer"]["name"]), 32)
        self.assertLessEqual(len(template["name"]["short"]), 30)
        self.assertLessEqual(len(template["description"]["short"]), 80)
        self.assertLessEqual(len(template["description"]["full"]), 4000)
        self.assertEqual(template["bots"][0]["botId"], "__TEAMS_BOT_CLIENT_ID__")
        self.assertEqual(set(template["bots"][0]["scopes"]), {"team", "groupChat"})
        self.assertNotIn("groupchat", template["bots"][0]["scopes"])
        actual = {
            (item["type"], item["name"])
            for item in template["authorization"]["permissions"]["resourceSpecific"]
        }
        self.assertEqual(actual, module.EXPECTED_RSC)

    def test_renderer_materializes_non_secret_identifiers_and_https_origin(self):
        rendered = module.render(TEMPLATE, GUID_APP, GUID_BOT, "https://sandbox.example.test")
        self.assertEqual(rendered["id"], GUID_APP)
        self.assertEqual(rendered["bots"][0]["botId"], GUID_BOT)
        self.assertEqual(rendered["webApplicationInfo"]["id"], GUID_BOT)
        self.assertEqual(rendered["webApplicationInfo"]["resource"], f"api://botid-{GUID_BOT}")
        self.assertEqual(rendered["developer"]["websiteUrl"], "https://sandbox.example.test")
        serialized = json.dumps(rendered)
        self.assertNotIn("__TEAMS_", serialized)
        self.assertNotIn("secret", serialized.lower())

    def test_renderer_rejects_invalid_ids_insecure_or_non_origin_urls_and_scope_drift(self):
        with self.assertRaisesRegex(ValueError, "GUID"):
            module.render(TEMPLATE, "not-a-guid", GUID_BOT, "https://sandbox.example.test")
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            module.render(TEMPLATE, GUID_APP, GUID_BOT, "http://sandbox.example.test")
        with self.assertRaisesRegex(ValueError, "without a path"):
            module.render(TEMPLATE, GUID_APP, GUID_BOT, "https://sandbox.example.test/tunnel/path")

        manifest = module.render(TEMPLATE, GUID_APP, GUID_BOT, "https://sandbox.example.test")
        manifest["bots"][0]["scopes"].append("personal")
        with self.assertRaisesRegex(ValueError, "scopes"):
            module.validate_manifest(manifest)

        manifest = module.render(TEMPLATE, GUID_APP, GUID_BOT, "https://sandbox.example.test")
        manifest["packageName"] = "com.unifiedsupportinbox.dev"
        with self.assertRaisesRegex(ValueError, "packageName"):
            module.validate_manifest(manifest)

    def test_renderer_rejects_schema_length_drift(self):
        manifest = module.render(TEMPLATE, GUID_APP, GUID_BOT, "https://sandbox.example.test")
        manifest["developer"]["name"] = "x" * 33
        with self.assertRaisesRegex(ValueError, "32-character"):
            module.validate_manifest(manifest)

    def test_renderer_cli_writes_only_rendered_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "manifest.json"
            original_argv = module.sys.argv
            module.sys.argv = [
                str(RENDERER),
                "--teams-app-id", GUID_APP,
                "--bot-client-id", GUID_BOT,
                "--public-base-url", "https://sandbox.example.test",
                "--output", str(output),
            ]
            try:
                self.assertEqual(module.main(), 0)
            finally:
                module.sys.argv = original_argv
            materialized = json.loads(output.read_text(encoding="utf-8"))
            module.validate_manifest(materialized)

    def test_environment_contract_keeps_teams_secret_out_of_process_environment(self):
        contract = json.loads(ENV_CONTRACT.read_text(encoding="utf-8"))
        self.assertIn("USI_TEAMS_CLIENT_ID", contract["backendVariables"])
        self.assertIn("USI_TEAMS_TENANT_ID", contract["backendVariables"])
        self.assertIn("USI_TEAMS_CLIENT_SECRET", contract["forbiddenProviderSecretEnvironmentNames"])
        self.assertNotIn("USI_TEAMS_CLIENT_SECRET", contract["backendVariables"])
        self.assertEqual(contract["integrationSecretPersistence"]["storedField"], "secret_ref")
        self.assertFalse(contract["integrationSecretPersistence"]["resolvedValueStoredInDatabase"])

    def test_guide_records_cli_secret_handoff_installation_and_ticket_boundary(self):
        guide = GUIDE.read_text(encoding="utf-8")
        required = (
            "teams app create",
            "--env \"$TEAMS_TMP_DIR/teams-created.env\"",
            "teams-client-secret",
            "USI_INTEGRATION_SECRETS_DIRECTORY",
            "teams app manifest upload",
            "`team` + `groupChat`",
            "`ChannelMessage.Read.Group` + `ChatMessage.Read.Chat`",
            "app installs successfully in one test team and one test group chat",
            "USI-181 owns the authenticated Teams callback runtime",
        )
        for fragment in required:
            self.assertIn(fragment, guide)
        self.assertNotIn("USI_TEAMS_CLIENT_SECRET=", guide)


if __name__ == "__main__":
    unittest.main()
