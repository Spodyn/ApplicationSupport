#!/usr/bin/env python3
"""Scan resolved Maven coordinates against OSV; emit no application data or secrets."""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

COORDINATE = re.compile(r"^\s+([^ :]+):([^ :]+):jar:(?:[^ :]+:)?([^ :]+):(compile|runtime|test|provided)(?:\s|$)")


def resolved_packages(text: str) -> list[dict]:
    packages = []
    for line in text.splitlines():
        match = COORDINATE.match(line)
        if match:
            packages.append({"package": {"ecosystem": "Maven", "name": f"{match[1]}:{match[2]}"}, "version": match[3]})
    if not packages:
        raise ValueError("No resolved Maven coordinates found; refusing an empty scan")
    return packages


def request_json(url: str, payload: dict | None = None) -> dict:
    request = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


def scan(packages: list[dict]) -> dict:
    results = request_json("https://api.osv.dev/v1/querybatch", {"queries": packages})["results"]
    if len(results) != len(packages):
        raise ValueError("Incomplete OSV response")
    findings = []
    details = {}
    for package, result in zip(packages, results):
        if result.get("next_page_token"):
            raise ValueError("Truncated OSV result requires manual investigation")
        for item in result.get("vulns", []):
            advisory_id = item["id"]
            if not re.fullmatch(r"[A-Za-z0-9._-]+", advisory_id):
                raise ValueError("Invalid advisory identifier")
            if advisory_id not in details:
                details[advisory_id] = request_json(f"https://api.osv.dev/v1/vulns/{advisory_id}")
            detail = details[advisory_id]
            if detail.get("withdrawn"):
                continue
            severity = detail.get("database_specific", {}).get("severity", "UNKNOWN").upper()
            findings.append({"package": package["package"]["name"], "version": package["version"],
                             "advisory": advisory_id, "severity": severity})
    return {"source": "https://api.osv.dev", "packagesScanned": len(packages), "findings": findings}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-list", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        result = scan(resolved_packages(args.maven_list.read_text(encoding="utf-8")))
        output = json.dumps(result, indent=2) + "\n"
        if args.report:
            args.report.write_text(output, encoding="utf-8")
        print(output, end="")
        # Unknown severity is not permission to pass an unreviewed advisory.
        return 1 if any(f["severity"] not in {"LOW", "MODERATE", "MEDIUM"} for f in result["findings"]) else 0
    except (OSError, ValueError, KeyError, TypeError):
        print("Dependency scan incomplete; inspect network/input and rerun. No passing result recorded.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
