"""Pure, fail-closed validation shared by CI and the restricted VPS receiver."""
import re

REPOSITORY = "Spodyn/ApplicationSupport"
REQUIRED = {"configuration", "backend", "integration", "infrastructure", "openapi", "web", "fullstack", "validate"}
IMAGE_NAMES = {
    "api": "ghcr.io/spodyn/applicationsupport-api",
    "web": "ghcr.io/spodyn/applicationsupport-web",
    "minio": "ghcr.io/spodyn/applicationsupport-minio",
    "mc": "ghcr.io/spodyn/applicationsupport-mc",
}


def parse_command(value: str) -> str | None:
    if value == "check":
        return None
    match = re.fullmatch(r"deploy ([0-9a-f]{40})", value)
    if not match:
        raise ValueError("Only check or deploy <40-character-main-SHA> is allowed")
    return match[1]


def validate_archive_member(name: str, directory: bool, link: bool) -> None:
    parent = directory and name.rstrip("/") in ("infra", "infra/staging")
    if (not parent and not name.startswith("infra/staging/")) or link or ".." in name.split("/") or "\\" in name:
        raise ValueError("Unexpected release archive member")


def require_ci(sha: str, main_sha: str, runs: list[dict]) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or sha != main_sha:
        raise ValueError("Deployment must identify the exact current main commit")
    # Do not mix stale attempts, duplicate check names or partial workflow runs.
    candidates = [r for r in runs if r.get("name") == "CI" and r.get("head_sha") == sha
                  and r.get("event") == "push" and r.get("head_branch") == "main"]
    if not candidates:
        raise ValueError("No main push CI run exists for this commit")
    latest = max(candidates, key=lambda r: (r.get("run_number", 0), r.get("run_attempt", 0)))
    if latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise ValueError("The latest main CI run has not succeeded")
    jobs = latest.get("jobs", [])
    for name in REQUIRED:
        matches = [j for j in jobs if j.get("name") == name]
        if len(matches) != 1 or matches[0].get("conclusion") != "success" or matches[0].get("status") != "completed":
            raise ValueError(f"Required exact-commit CI job is not successful: {name}")


def validate_images(sha: str, release: dict) -> dict[str, str]:
    if release.get("sha") != sha or set(release.get("images", {})) != set(IMAGE_NAMES):
        raise ValueError("Release identity/image set does not match")
    for name, prefix in IMAGE_NAMES.items():
        if not re.fullmatch(re.escape(prefix) + r"@sha256:[0-9a-f]{64}", release["images"][name]):
            raise ValueError(f"An immutable image in the approved repository is required: {name}")
    return release["images"]
