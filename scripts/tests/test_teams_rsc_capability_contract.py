import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "docs" / "poc" / "teams-rsc" / "manifest.contract.json"
ADR_PATH = ROOT / "docs" / "TEAMS_RSC_CAPABILITY.md"
INTEGRATIONS_PATH = ROOT / "docs" / "INTEGRATIONS.md"
DECISIONS_PATH = ROOT / "docs" / "DECISION_REGISTRY.md"


class TeamsRscCapabilityContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
        cls.adr = ADR_PATH.read_text(encoding="utf-8")
        cls.integrations = INTEGRATIONS_PATH.read_text(encoding="utf-8")
        cls.decisions = DECISIONS_PATH.read_text(encoding="utf-8")
        cls.contexts = {item["id"]: item for item in cls.contract["contexts"]}

    def test_contract_is_bound_to_usi_179_and_frozen_teams_scope(self):
        self.assertEqual(self.contract["ticket"], "USI-179")
        self.assertEqual(self.contract["productScopeDecision"], "DEC-TEAMS-001")
        self.assertEqual(self.contract["reviewedOn"], "2026-08-29")
        self.assertIn("DEC-TEAMS-001 | scope | standard channels + group chats; no private/shared", self.decisions)
        self.assertIn("- standard Teams channels;", self.integrations)
        self.assertIn("- group chats.", self.integrations)
        self.assertIn("- private channels;", self.integrations)
        self.assertIn("- shared channels.", self.integrations)

    def test_manifest_requests_only_least_privilege_receive_all_rsc_permissions(self):
        manifest = self.contract["manifest"]
        permissions = manifest["resourceSpecificPermissions"]
        actual = {(item["name"], item["type"], item["resource"]) for item in permissions}
        self.assertEqual(
            actual,
            {
                ("ChannelMessage.Read.Group", "Application", "team"),
                ("ChatMessage.Read.Chat", "Application", "chat"),
            },
        )
        self.assertEqual(set(manifest["botScopes"]), {"team", "groupchat"})
        self.assertNotIn("personal", manifest["botScopes"])
        self.assertTrue(manifest["requiresWebApplicationInfo"])
        self.assertFalse(manifest["credentialsCommitted"])

        requested_names = {item["name"] for item in permissions}
        forbidden = set(manifest["forbiddenTenantWideMessagePermissions"])
        self.assertTrue(forbidden.isdisjoint(requested_names))
        self.assertFalse(any(name.endswith(".Read.All") for name in requested_names))

    def test_supported_v1_contexts_receive_without_mention(self):
        standard = self.contexts["standard_channel"]
        self.assertEqual(standard["v1Disposition"], "SUPPORTED")
        self.assertEqual(standard["installScope"], "team")
        self.assertEqual(standard["rscPermission"], "ChannelMessage.Read.Group")
        self.assertIs(standard["receiveWithoutMention"], True)
        self.assertEqual(standard["liveSandboxSmokeOwner"], "USI-180")

        group_chat = self.contexts["group_chat"]
        self.assertEqual(group_chat["v1Disposition"], "SUPPORTED")
        self.assertEqual(group_chat["installScope"], "groupchat")
        self.assertEqual(group_chat["rscPermission"], "ChatMessage.Read.Chat")
        self.assertIs(group_chat["receiveWithoutMention"], True)
        self.assertIs(group_chat["requiresNewOrReinstallationAfterReceiveAllPermission"], True)
        self.assertEqual(group_chat["liveSandboxSmokeOwner"], "USI-180")

    def test_private_and_shared_channels_are_explicitly_deferred_not_silently_enabled(self):
        for context_id in ("private_channel", "shared_channel"):
            context = self.contexts[context_id]
            self.assertEqual(context["v1Disposition"], "DEFERRED")
            self.assertEqual(context["vendorAppCapability"], "SUPPORTED_WITH_CHANNEL_ENABLEMENT")
            self.assertEqual(context["installScope"], "channel_specific_after_host_team_install")
            self.assertIsNone(context["receiveWithoutMention"])
            self.assertIn("explicit product scope decision", context["requiredBeforeFutureEnablement"])
            self.assertIn("non-mention inbound delivery test", context["requiredBeforeFutureEnablement"])

    def test_personal_scope_is_not_part_of_v1_poc(self):
        personal = self.contexts["personal_chat"]
        self.assertEqual(personal["v1Disposition"], "OUT_OF_SCOPE")
        self.assertNotIn(personal["installScope"], self.contract["manifest"]["botScopes"])

    def test_adr_records_install_model_scope_boundary_and_secret_boundary(self):
        required_fragments = (
            "`ChannelMessage.Read.Group`",
            "`ChatMessage.Read.Chat`",
            "`team`",
            "`groupchat`",
            "Private channels and shared channels remain **deferred/out of v1**",
            "No tenant credentials or production Microsoft 365 credentials are introduced",
            "E18-T02 / USI-180",
            "E18-T03 / USI-181",
        )
        for fragment in required_fragments:
            self.assertIn(fragment, self.adr)

    def test_vendor_evidence_is_official_microsoft_learn_only(self):
        sources = self.contract["sources"]
        self.assertGreaterEqual(len(sources), 4)
        self.assertTrue(all(source.startswith("https://learn.microsoft.com/") for source in sources))
        for source in sources:
            self.assertIn(source, self.adr)


if __name__ == "__main__":
    unittest.main()
