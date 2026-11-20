#!/usr/bin/env python3
"""Fail on any HIGH/CRITICAL image finding; no Docker socket or runtime secret mounts."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

SCANNER = "aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb"


def scan(images: list[str]) -> None:
    if not images:
        raise ValueError("At least one immutable release/local test image is required")
    with tempfile.TemporaryDirectory(prefix="usi-image-scan-") as temporary:
        directory = Path(temporary).resolve()
        if directory.parent != Path(tempfile.gettempdir()).resolve() or not directory.name.startswith("usi-image-scan-"):
            raise ValueError("Unexpected disposable scan directory")
        (directory / "cache").mkdir()
        user = [] if os.name == "nt" else ["--user", f"{os.getuid()}:{os.getgid()}"]
        failed = False
        for index, image in enumerate(images):
            archive, report = directory / f"image-{index}.tar", directory / f"image-{index}.json"
            subprocess.run(["docker", "save", "--output", str(archive), image], check=True)
            result = subprocess.run(["docker", "run", "--rm", *user, "--mount", f"type=bind,source={directory},target=/scan",
                SCANNER, "--quiet", "--cache-dir", "/scan/cache", "image", "--input", f"/scan/{archive.name}",
                "--timeout", "15m", "--scanners", "vuln", "--severity", "HIGH,CRITICAL", "--exit-code", "1",
                "--format", "json", "--output", f"/scan/{report.name}"], check=False)
            if not report.is_file():
                raise RuntimeError("Image vulnerability scan did not produce a report")
            data = json.loads(report.read_text(encoding="utf-8"))
            findings = [v for r in data.get("Results", []) for v in r.get("Vulnerabilities", []) or []]
            print(f"{image}: HIGH/CRITICAL findings={len(findings)}", flush=True)
            for finding in findings:
                print(f"  {finding['Severity']} {finding['VulnerabilityID']} {finding['PkgName']}", flush=True)
            if result.returncode != 0 or findings:
                failed = True
            archive.unlink()
        if failed:
            raise RuntimeError("Image security gate failed; no vulnerability suppressions were applied")


if __name__ == "__main__":
    scan(sys.argv[1:])
