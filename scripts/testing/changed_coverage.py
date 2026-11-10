"""Require covered added executable lines in the explicitly scoped security/transport LCOV report."""
from pathlib import Path
import argparse
import re
import subprocess


def covered_lines(lcov: str) -> dict[str, dict[int, int]]:
    result = {}; current = None
    for line in lcov.splitlines():
        if line.startswith('SF:'):
            current = 'apps/web/' + line[3:].replace('\\', '/')
            result[current] = {}
        elif current and line.startswith('DA:'):
            number, hits, *_ = line[3:].split(','); result[current][int(number)] = int(hits)
    return result


def added_lines(diff: str) -> dict[str, set[int]]:
    result = {}; current = None; number = 0
    for line in diff.splitlines():
        if line.startswith('+++ b/'):
            current = line[6:]; result.setdefault(current, set())
        elif line.startswith('@@'):
            match = re.search(r'\+(\d+)', line)
            if match: number = int(match[1])
        elif line.startswith('+') and current:
            result[current].add(number); number += 1
        elif line.startswith(' '): number += 1
    return result


def uncovered_changes(diff: str, lcov: str) -> list[str]:
    coverage = covered_lines(lcov)
    return [f'{name}:{line}' for name, lines in added_lines(diff).items() if name in coverage
            for line in sorted(lines) if line in coverage[name] and coverage[name][line] == 0]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-ref', required=True)
    parser.add_argument('--lcov', default='apps/web/coverage/lcov.info')
    args = parser.parse_args()
    # Git resolves the exact commit; no shell interpolation or arbitrary option injection.
    base = subprocess.run(['git', 'rev-parse', '--verify', '--end-of-options', args.base_ref + '^{commit}'], check=True, capture_output=True, text=True).stdout.strip()
    diff = subprocess.run(['git', 'diff', '--unified=0', base, '--', 'apps/web'], check=True, capture_output=True, text=True).stdout
    lcov = Path(args.lcov).read_text(encoding='utf8')
    if not covered_lines(lcov): raise SystemExit('Empty LCOV cannot establish changed-line coverage')
    failures = uncovered_changes(diff, lcov)
    if failures: raise SystemExit('Uncovered changed executable lines:\n' + '\n'.join(failures))
    print('Changed executable lines in scoped security/transport modules are covered.')
