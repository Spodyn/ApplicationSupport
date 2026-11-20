"""Deployment boundaries: exact main CI, immutable images, restricted SSH and private services."""
from copy import deepcopy
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra/staging"))
from deployment_policy import IMAGE_NAMES, REQUIRED, parse_command, require_ci, validate_archive_member, validate_images
from init_state import initialize

SHA = "a" * 40


def ci():
    return [{"id": 1, "name": "CI", "head_sha": SHA, "head_branch": "main", "event": "push", "run_number": 5,
             "status": "completed", "conclusion": "success", "jobs": [
                 {"name": name, "status": "completed", "conclusion": "success"} for name in REQUIRED]}]


class StagingDeploymentPolicyTests(unittest.TestCase):
    def test_requires_one_complete_successful_main_ci_run(self):
        require_ci(SHA, SHA, ci())

    def test_rejects_stale_or_invalid_commit(self):
        for sha, main in ((SHA, "b" * 40), ("a" * 39, "a" * 39), (SHA + ";id", SHA)):
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                require_ci(sha, main, ci())

    def test_rejects_missing_ci_and_pull_request_success(self):
        for runs in ([], [{**ci()[0], "event": "pull_request"}], [{**ci()[0], "head_branch": "feature"}]):
            with self.subTest(runs=bool(runs)), self.assertRaises(ValueError):
                require_ci(SHA, SHA, runs)

    def test_each_required_job_must_actually_succeed(self):
        for state in ("failure", "skipped", "cancelled", None):
            for name in REQUIRED:
                runs = ci()
                next(j for j in runs[0]["jobs"] if j["name"] == name)["conclusion"] = state
                with self.subTest(job=name, state=state), self.assertRaises(ValueError):
                    require_ci(SHA, SHA, runs)

    def test_missing_and_duplicate_job_names_fail_closed(self):
        for duplicate in (False, True):
            runs = ci()
            removed = runs[0]["jobs"].pop()
            if duplicate:
                runs[0]["jobs"].extend([removed, removed])
            with self.assertRaises(ValueError):
                require_ci(SHA, SHA, runs)

    def test_failed_new_attempt_cannot_use_an_older_green_run(self):
        runs = ci()
        newer = deepcopy(runs[0])
        newer.update(run_number=6, conclusion="failure")
        with self.assertRaises(ValueError):
            require_ci(SHA, SHA, runs + [newer])

    def test_only_digest_images_in_approved_repositories_are_allowed(self):
        release = {"sha": SHA, "images": {name: prefix + "@sha256:" + "c" * 64 for name, prefix in IMAGE_NAMES.items()}}
        self.assertEqual(set(validate_images(SHA, release)), set(IMAGE_NAMES))
        for value in ("ghcr.io/attacker/api@sha256:" + "c" * 64, IMAGE_NAMES["api"] + ":latest", IMAGE_NAMES["api"] + "@sha256:" + "c" * 64 + ";id"):
            invalid = deepcopy(release)
            invalid["images"]["api"] = value
            with self.assertRaises(ValueError):
                validate_images(SHA, invalid)
        with self.assertRaises(ValueError):
            validate_images("b" * 40, release)

    def test_forced_command_does_not_allow_shell_scp_or_forwarding_commands(self):
        self.assertIsNone(parse_command("check"))
        self.assertEqual(parse_command("deploy " + SHA), SHA)
        for value in ("bash", "scp -t /root", "check; id", "deploy " + SHA + "\nid", "deploy $(id)", "deploy " + SHA + " "):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_command(value)

    def test_archive_accepts_git_parent_directories_but_rejects_escape_and_links(self):
        for name, directory in (("infra/", True), ("infra/staging/", True), ("infra/staging/compose.yaml", False)):
            validate_archive_member(name, directory, False)
        for name, link in (("/etc/passwd", False), ("infra/staging/../../etc/passwd", False), ("infra/staging/link", True), ("infra/staging/..\\escape", False)):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_archive_member(name, False, link)


class StagingStateTests(unittest.TestCase):
    def test_state_reinitialization_preserves_credentials_and_ca(self):
        def tool(args, **kwargs):
            for option in ("-keyout", "-out", "-o"):
                if option in args:
                    Path(args[args.index(option) + 1]).write_text("test-only-generated-material", encoding="utf-8")
        with tempfile.TemporaryDirectory() as directory, patch("init_state.subprocess.run", side_effect=tool), patch("init_state.os.chown", create=True):
            state = Path(directory) / "state"
            initialize(state, "admin@example.test")
            before = {str(p.relative_to(state)): p.read_bytes() for p in state.rglob("*") if p.is_file()}
            initialize(state, "admin@example.test")
            after = {str(p.relative_to(state)): p.read_bytes() for p in state.rglob("*") if p.is_file()}
            self.assertEqual(before, after)
            self.assertGreaterEqual(len((state / "core/bootstrap-admin-password").read_text().strip()), 12)

    def test_state_rejects_email_injection(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            initialize(Path(directory) / "state", "admin@example.test\nUSI_API_IMAGE=attacker")


class StagingWorkflowBoundaryTests(unittest.TestCase):
    def test_windows_checkout_keeps_linux_entrypoint_shebang(self):
        content = subprocess.check_output(["git", "-c", "core.autocrlf=true", "cat-file", "--filters", "HEAD:infra/staging/api-entrypoint.sh"], cwd=ROOT)
        self.assertTrue(content.startswith(b"#!/bin/sh\n"), "Windows checkout must not turn the container shebang into /bin/sh\\r")

    def test_deployment_gate_matches_every_required_ci_job(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8").split("\njobs:", 1)[1]
        self.assertEqual(set(re.findall(r"^  ([a-z]+):$", workflow, re.MULTILINE)), REQUIRED)

    def test_deployment_checks_out_only_main_and_keeps_secrets_out_of_build(self):
        workflow = (ROOT / ".github/workflows/deploy-staging.yml").read_text(encoding="utf-8")
        build, deploy = workflow.split("\n  deploy:", 1)
        self.assertNotIn("secrets.", build)
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("inputs.ref", workflow)
        self.assertEqual(workflow.count("ref: main"), 2)
        self.assertEqual(workflow.count("persist-credentials: false"), 2)
        self.assertIn("name: staging", deploy)
        self.assertIn("StrictHostKeyChecking=yes", deploy)
        self.assertIn("AllowedIPs = 10.77.0.1/32", deploy)

    def test_wireguard_conf_is_root_only_and_in_the_standard_directory(self):
        workflow = (ROOT / ".github/workflows/deploy-staging.yml").read_text(encoding="utf-8")
        self.assertIn("WIREGUARD_CONFIG: /etc/wireguard/usi-actions.conf", workflow)
        self.assertIn('sudo install -m 0600 /dev/null "$WIREGUARD_CONFIG"', workflow)
        self.assertIn('sudo wg-quick up "$WIREGUARD_CONFIG"', workflow)
        self.assertIn('sudo rm -f "$WIREGUARD_CONFIG"', workflow)

    def test_both_required_ci_and_release_build_enforce_container_scan(self):
        self.assertIn("scan_images.py", (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        self.assertIn("scan_images.py", (ROOT / "infra/staging/build-images.sh").read_text(encoding="utf-8"))
        scanner = (ROOT / "infra/staging/scan_images.py").read_text(encoding="utf-8")
        self.assertIn('"HIGH,CRITICAL"', scanner)
        self.assertNotIn("--ignore-unfixed", scanner)
        self.assertNotIn("docker.sock", scanner)


class StagingComposeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Explicit clean env prevents Compose from consulting any local .env.
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "staging.env"
            env_file.write_text("USI_STATE_DIRECTORY=" + Path(directory).as_posix() + "\n" + "".join(
                f"USI_{name.upper()}_IMAGE={prefix}@sha256:{'c' * 64}\n" for name, prefix in IMAGE_NAMES.items()), encoding="utf-8")
            process_env = {key: value for key, value in os.environ.items() if not key.startswith(("USI_", "COMPOSE_", "STAGING_"))}
            cls.model = json.loads(subprocess.check_output(["docker", "compose", "--env-file", str(env_file), "-f", str(ROOT / "infra/staging/compose.yaml"), "config", "--format", "json"], env=process_env, text=True))

    def test_only_caddy_has_published_ports(self):
        self.assertEqual({name for name, service in self.model["services"].items() if service.get("ports")}, {"caddy"})
        self.assertEqual({p["target"] for p in self.model["services"]["caddy"]["ports"]}, {80, 443})
        self.assertTrue(self.model["networks"]["data"]["internal"])

    def test_api_uses_mounted_secrets_and_verified_private_tls(self):
        api = self.model["services"]["api"]
        self.assertEqual(api["environment"]["SPRING_PROFILES_ACTIVE"], "staging")
        self.assertEqual(api["environment"]["USI_OBJECT_STORAGE_ENDPOINT"], "https://minio:9000")
        self.assertEqual(api["environment"]["USI_CORE_SECRETS_DIRECTORY"], "/run/usi-core/")
        self.assertFalse(any(name.endswith(("PASSWORD", "SECRET_KEY", "BOT_TOKEN")) for name in api["environment"]))
        self.assertTrue(api["read_only"])
        self.assertTrue(all(v.get("read_only") for v in api["volumes"]))

    def test_data_and_acme_state_are_persistent(self):
        self.assertEqual(set(self.model["volumes"]), {"postgres_data", "rabbitmq_data", "minio_data", "clamav_data", "caddy_data", "caddy_config"})
        self.assertEqual(self.model["services"]["api"]["environment"]["STAGING_BOOTSTRAP_ENABLED"] if "STAGING_BOOTSTRAP_ENABLED" in self.model["services"]["api"]["environment"] else self.model["services"]["api"]["environment"]["USI_BOOTSTRAP_ADMIN_ENABLED"], "false")


if __name__ == "__main__":
    unittest.main()
