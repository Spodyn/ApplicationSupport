#!/usr/bin/env python3
"""Fail when tracked repository content resembles credentials or private keys."""

from __future__ import annotations

import argparse
import math
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
MAX_TEXT_BYTES = 2 * 1024 * 1024
SENSITIVE_PUBLIC_SUFFIXES = (
    "SECRET",
    "TOKEN",
    "PASSWORD",
    "PASSWD",
    "CREDENTIAL",
    "PRIVATE_KEY",
    "SIGNING_KEY",
    "ACCESS_KEY",
)
SAFE_EXAMPLE_MARKERS = (
    "dummy",
    "example",
    "dev_only",
    "dev-only",
    "usi-dev-",
    "test_only",
    "test-only",
    "local_only",
    "local-only",
    "change_me",
    "change-me",
    "changeme",
    "placeholder",
    "redacted",
    "fake",
    "sample",
)


@dataclass(frozen=True, order=True)
class Finding:
    path: str
    line: int
    detector: str


def _token_patterns() -> tuple[tuple[str, re.Pattern[str]], ...]:
    # Split recognizable prefixes so the scanner's own source is not a fixture.
    patterns = (
        ("private-key", "-----BEGIN " + r"(?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
        ("aws-access-key", "A" + r"(?:KI|SI)A[0-9A-Z]{16}"),
        ("github-token", "gh" + r"[pousr]_[A-Za-z0-9]{20,}"),
        ("gitlab-token", "gl" + r"pat-[A-Za-z0-9_-]{20,}"),
        ("google-api-key", "AI" + r"za[0-9A-Za-z_-]{30,}"),
        ("slack-token", "xo" + r"x[baprs]-[A-Za-z0-9-]{10,}"),
        ("telegram-bot-token", r"\b\d{8,12}:[A-Za-z0-9_-]{30,}\b"),
    )
    return tuple((name, re.compile(pattern)) for name, pattern in patterns)


TOKEN_PATTERNS = _token_patterns()
PUBLIC_NAME = re.compile(r"NEXT_PUBLIC_[A-Z0-9_]+")
GENERIC_ASSIGNMENT = re.compile(
    r"(?ix)"
    r"(?:^|[\s{,])"
    r"[\"']?"
    r"(?P<key>[A-Za-z0-9_.-]*(?:password|passwd|client[_-]?secret|signing[_-]?secret|private[_-]?key|access[_-]?key|api[_-]?token|bot[_-]?token)[A-Za-z0-9_.-]*)"
    r"[\"']?"
    r"\s*[=:]\s*"
    r"(?P<quote>[\"']?)"
    r"(?P<value>[^\s,}\"']{8,})"
)


def _safe_placeholder(value: str) -> bool:
    lowered = value.lower()
    if any(marker in lowered for marker in SAFE_EXAMPLE_MARKERS):
        return True
    return (
        value.startswith(("${", "$${", "${{", "<", "process.env", "configtree:"))
        or value.endswith("}")
        or value in {"********", "xxxxxxxx"}
    )


def _entropy(value: str) -> float:
    if not value:
        return 0.0
    counts = Counter(value)
    return -sum(
        (count / len(value)) * math.log2(count / len(value))
        for count in counts.values()
    )


def scan_text(path: str, text: str) -> list[Finding]:
    findings: set[Finding] = set()
    for line_number, line in enumerate(text.splitlines(), start=1):
        for detector, pattern in TOKEN_PATTERNS:
            if pattern.search(line):
                findings.add(Finding(path, line_number, detector))

        for public_name in PUBLIC_NAME.findall(line):
            if any(suffix in public_name for suffix in SENSITIVE_PUBLIC_SUFFIXES):
                findings.add(Finding(path, line_number, "sensitive-next-public-name"))

        for match in GENERIC_ASSIGNMENT.finditer(line):
            value = match.group("value")
            if _safe_placeholder(value):
                continue
            # Short, low-entropy labels such as enum names are not credentials.
            if len(value) >= 12 and _entropy(value) >= 3.0:
                findings.add(Finding(path, line_number, "literal-secret-assignment"))

    return sorted(findings)


def _git(repository_root: Path, *arguments: str, input_text: str | None = None) -> str:
    completed = subprocess.run(
        ("git", "-C", str(repository_root), *arguments),
        check=True,
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout


def _worktree_paths(repository_root: Path) -> list[str]:
    output = _git(
        repository_root,
        "ls-files",
        "-z",
        "--cached",
        "--others",
        "--exclude-standard",
    )
    return sorted(path for path in output.split("\0") if path)


def _tracked_paths(repository_root: Path) -> set[str]:
    output = _git(repository_root, "ls-files", "-z", "--cached")
    return {path for path in output.split("\0") if path}


def _read_text(path: Path) -> str | None:
    try:
        if path.stat().st_size > MAX_TEXT_BYTES:
            return None
        content = path.read_bytes()
    except (FileNotFoundError, OSError):
        return None
    if b"\0" in content:
        return None
    try:
        return content.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _history_blobs(repository_root: Path) -> Iterable[tuple[str, str, int]]:
    objects = _git(repository_root, "rev-list", "--objects", "--all").splitlines()
    object_paths: dict[str, str] = {}
    for line in objects:
        object_id, separator, path = line.partition(" ")
        if separator and path:
            object_paths.setdefault(object_id, path)
    if not object_paths:
        return

    checks = _git(
        repository_root,
        "cat-file",
        "--batch-check=%(objectname) %(objecttype) %(objectsize)",
        input_text="".join(f"{object_id}\n" for object_id in object_paths),
    )
    for line in checks.splitlines():
        object_id, object_type, raw_size = line.split()
        size = int(raw_size)
        if object_type == "blob" and size <= MAX_TEXT_BYTES:
            yield object_id, object_paths[object_id], size


def _scan_history(repository_root: Path) -> list[Finding]:
    findings: set[Finding] = set()
    for object_id, path, _size in _history_blobs(repository_root):
        content = subprocess.run(
            ("git", "-C", str(repository_root), "cat-file", "blob", object_id),
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout
        if b"\0" in content:
            continue
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError:
            continue
        history_path = f"{path}@{object_id[:12]}"
        findings.update(scan_text(history_path, text))
        if Path(path).name.startswith(".env") and Path(path).name != ".env.example":
            findings.add(Finding(history_path, 0, "tracked-env-file"))
    return sorted(findings)


def scan_repository(
    repository_root: Path = REPOSITORY_ROOT, *, include_history: bool = True
) -> list[Finding]:
    findings: set[Finding] = set()
    tracked_paths = _tracked_paths(repository_root)
    for relative_path in _worktree_paths(repository_root):
        if (
            relative_path in tracked_paths
            and Path(relative_path).name.startswith(".env")
            and Path(relative_path).name != ".env.example"
        ):
            findings.add(Finding(relative_path, 0, "tracked-env-file"))
        text = _read_text(repository_root / relative_path)
        if text is not None:
            findings.update(scan_text(relative_path, text))
    if include_history:
        findings.update(_scan_history(repository_root))
    return sorted(findings)


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scan current and historical USI repository content for secrets."
    )
    parser.add_argument(
        "--no-history",
        action="store_true",
        help="Scan only tracked/untracked non-ignored worktree files.",
    )
    return parser.parse_args()


def main() -> int:
    arguments = _arguments()
    try:
        findings = scan_repository(include_history=not arguments.no_history)
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"Secret scan could not complete: {error}", file=sys.stderr)
        return 2

    if findings:
        print("Secret scan failed; values are intentionally redacted:", file=sys.stderr)
        for finding in findings:
            location = (
                f"{finding.path}:{finding.line}" if finding.line else finding.path
            )
            print(f"- {location} [{finding.detector}]", file=sys.stderr)
        return 1

    scope = "worktree and available Git history" if not arguments.no_history else "worktree"
    print(f"Secret scan passed for {scope}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
