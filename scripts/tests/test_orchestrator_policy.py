"""Contract regression checks for the owner-approved autonomous merge policy.

Deliberately standard-library-only: runs in the existing CI configuration job.
It asserts safety-critical policy flags and source consistency; it does not
replace a full YAML parser or the required end-to-end automation smoke test.
"""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class OrchestratorPolicyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = (ROOT / "docs/orchestrator-policy.yaml").read_text(encoding="utf-8")
        cls.contract = (ROOT / "docs/ORCHESTRATOR_CONTRACT.md").read_text(encoding="utf-8")
        cls.agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")

    def test_owner_decision_is_tracked_in_all_operational_sources(self):
        for source in (self.policy, self.contract, self.agents):
            with self.subTest(source=source[:60]):
                self.assertIn("USI-247", source)
                self.assertIn("2026-10-29", source)

    def test_target_uses_actual_ci_not_fabricated_reviews(self):
        self.assertIn("owner_preference: ci_gated_automatic_integration_without_manual_reviewer", self.policy)
        self.assertIn("manual_review_required_by_default: false", self.policy)
        self.assertIn("never_fabricate_approval: true", self.policy)
        self.assertIn("required_workflow: CI", self.policy)
        for job in ("configuration", "backend", "integration", "infrastructure", "openapi", "validate"):
            self.assertRegex(self.policy, rf"required_workflow_jobs: \[[^\]]*\b{job}\b")
        self.assertIn("full_required_ci_and_security_green", self.policy)
        self.assertIn("validated_head_sha_equals_current_pr_head", self.policy)
        self.assertIn("actual_approval_if_still_required_by_effective_rule", self.policy)

    def test_protected_activation_and_recovery_remain_enforced(self):
        self.assertIn("unattended_merge_enabled: false", self.policy)
        self.assertIn("end_to_end_nonproduction_merge_path_validated", self.policy)
        self.assertIn("effective_github_protection_satisfied", self.policy)
        self.assertIn("never_treat_not_run_or_blocked_as_pass: true", self.policy)
        self.assertIn("done_only_after_actual_merge_and_required_dod: true", self.policy)
        self.assertIn("may_merge: false", self.policy)
        self.assertIn("leases_and_heartbeat: true", self.policy)
        self.assertIn("pitr_or_destructive_recovery", self.policy)
        self.assertIn("Do przeglądu", self.contract)
        self.assertIn("BLOCKED", self.contract)

    def test_yaml_has_no_tabs_or_duplicate_top_level_keys(self):
        self.assertNotIn(chr(9), self.policy)
        top_level = re.findall(r"(?m)^([a-z][a-z0-9_]*):", self.policy)
        self.assertEqual(len(top_level), len(set(top_level)))
        self.assertTrue({"workers", "automation", "merge_gate", "production_boundary"}.issubset(set(top_level)))


if __name__ == "__main__":
    unittest.main()
