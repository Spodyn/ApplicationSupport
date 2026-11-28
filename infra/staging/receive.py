#!/usr/bin/env python3
"""Root-owned forced command: check or deploy <exact-main-SHA>, only via VPN SSH key."""
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

sys.path.insert(0, "/usr/local/lib/usi-staging")
from deployment_policy import parse_command, validate_archive_member, validate_images
from check_ci import check

ROOT = Path("/srv/usi-staging")


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main() -> None:
    if len(sys.argv) != 2:
        raise ValueError("One forced command argument is required")
    sha = parse_command(sys.argv[1])
    os.environ.clear()
    os.environ.update({"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "HOME": "/root"})
    if sha is None:
        run("docker", "version", "--format", "{{.Server.Version}}")
        print("Restricted staging SSH command reachable through WireGuard; no deployment performed.")
        return
    raw = sys.stdin.buffer.read(65537)
    if len(raw) > 65536:
        raise ValueError("Release envelope too large")
    envelope = json.loads(raw)
    images = validate_images(sha, envelope)
    token = envelope.pop("registryToken", "")
    actor = envelope.pop("registryUser", "")
    if not isinstance(token, str) or not token or len(token) > 4096 or not re.fullmatch(r"[A-Za-z0-9-]{1,90}(?:\[bot\])?", actor):
        raise ValueError("Ephemeral read-only registry credentials are required")
    ROOT.mkdir(mode=0o700, exist_ok=True)
    if shutil.disk_usage(ROOT).free < 8 * 1024**3:
        raise ValueError("At least 8 GiB free disk is required before pulling a release")
    with (ROOT / "deploy.lock").open("w") as lock, tempfile.TemporaryDirectory(prefix="usi-registry-", dir="/run") as registry:
        fcntl.flock(lock, fcntl.LOCK_EX)
        os.environ["GH_TOKEN"] = token
        check(sha)
        del os.environ["GH_TOKEN"]
        repo = ROOT / "repository.git"
        if not repo.exists():
            run("git", "init", "--bare", str(repo))
            run("git", "--git-dir", str(repo), "remote", "add", "origin", "https://github.com/Spodyn/ApplicationSupport.git")
        run("git", "--git-dir", str(repo), "fetch", "--depth=1", "origin", "main")
        fetched = subprocess.check_output(["git", "--git-dir", str(repo), "rev-parse", "FETCH_HEAD"], text=True).strip()
        if fetched != sha:
            raise ValueError("Main advanced; dispatch a fresh deployment")
        release = ROOT / "releases" / sha
        release.mkdir(parents=True, mode=0o700, exist_ok=True)
        archive = run("git", "--git-dir", str(repo), "archive", sha, "infra/staging", stdout=subprocess.PIPE).stdout
        import io
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            for member in source.getmembers():
                validate_archive_member(member.name, member.isdir(), member.issym() or member.islnk())
            source.extractall(release, filter="data")
        env_file = release / "release.env"
        env_file.write_text("".join(f"USI_{name.upper() if name != 'mc' else 'MC'}_IMAGE={image}\n" for name, image in images.items()), encoding="utf-8")
        os.chmod(env_file, 0o600)
        os.environ["DOCKER_CONFIG"] = registry
        run("docker", "login", "ghcr.io", "--username", actor, "--password-stdin", input=token.encode(), stdout=subprocess.DEVNULL)
        for name, image in images.items():
            run("docker", "pull", image)
            label = subprocess.check_output(["docker", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', image], text=True).strip()
            if label != sha:
                raise ValueError(f"Image revision mismatch: {name}")
        # Pulling can take minutes; refuse a newly stale main before migration.
        os.environ["GH_TOKEN"] = token
        check(sha)
        del os.environ["GH_TOKEN"]
        del token
        os.environ["USI_EXPECTED_REVISION"] = sha
        run("bash", str(release / "infra/staging/deploy.sh"))


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # Do not echo untrusted envelope, cookies, credentials or captured output.
        print(f"Staging deployment refused/failed ({type(failure).__name__}). Inspect protected host diagnostics.", file=sys.stderr)
        raise SystemExit(1)
