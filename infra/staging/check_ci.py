#!/usr/bin/env python3
"""Verify a complete main push CI run. Does not trust deployment-workflow success."""
import argparse
import json
import os
import urllib.request
from deployment_policy import REPOSITORY, require_ci


def api(path: str):
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "usi-staging-gate"}
    token = os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request("https://api.github.com/repos/" + REPOSITORY + path, headers=headers), timeout=20) as response:
        return json.load(response)


def check(sha: str) -> None:
    main = api("/git/ref/heads/main")["object"]["sha"]
    runs = api("/actions/runs?head_sha=" + sha + "&event=push&per_page=100")["workflow_runs"]
    candidates = [r for r in runs if r.get("name") == "CI" and r.get("head_branch") == "main"]
    if candidates:
        latest = max(candidates, key=lambda r: (r.get("run_number", 0), r.get("run_attempt", 0)))
        latest["jobs"] = api(f"/actions/runs/{latest['id']}/jobs?filter=latest&per_page=100")["jobs"]
    require_ci(sha, main, runs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sha")
    args = parser.parse_args()
    check(args.sha)
    print("All eight CI jobs passed for the exact current main commit.")
