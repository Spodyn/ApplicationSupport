"""Exercise real retry-policy tests against three isolated, compiling behavioral mutations."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path('src/main/java/com/unifiedsupportinbox/messaging/internal/MessageRetryPolicy.java')
ORIGINAL = '"TRANSIENT".equals(category) || "PERMANENT".equals(category) && "INTEGRATION_DISABLED".equals(code)'
MUTANTS = {
    'drop-transient': '"PERMANENT".equals(category) && "INTEGRATION_DISABLED".equals(code)',
    'allow-all-permanent': '"TRANSIENT".equals(category) || "PERMANENT".equals(category)',
    'deny-all': 'false',
}


def run():
    evidence = []
    # Only repository sources/wrapper: never copy local configuration, data, or existing reports.
    with tempfile.TemporaryDirectory(prefix='usi-mutation-') as temporary:
        sandbox = Path(temporary) / 'api'
        shutil.copytree(ROOT / 'apps/api', sandbox,
                        ignore=shutil.ignore_patterns('target', '.env*', 'node_modules'))
        source = sandbox / SOURCE
        original = source.read_text(encoding='utf8')
        if original.count(ORIGINAL) != 1:
            raise SystemExit('Retry policy changed; review mutation operators before running')
        command = (['C:/Program Files/Git/bin/bash.exe', './mvnw'] if os.name == 'nt' else ['./mvnw'])
        command += ['--batch-mode', '-q', 'test', '-Dtest=MessageRetryPolicyTests']
        for name, expression in [('control', ORIGINAL), *MUTANTS.items()]:
            source.write_text(original.replace(ORIGINAL, expression), encoding='utf8')
            report = sandbox / 'target/surefire-reports/TEST-com.unifiedsupportinbox.messaging.internal.MessageRetryPolicyTests.xml'
            report.unlink(missing_ok=True)
            completed = subprocess.run(command, cwd=sandbox, capture_output=True, timeout=180)
            suite = ET.parse(report).getroot() if report.exists() else None
            tests = int(suite.get('tests', '0')) if suite is not None else 0
            failures = int(suite.get('failures', '0')) if suite is not None else 0
            errors = int(suite.get('errors', '0')) if suite is not None else 0
            valid = tests >= 9 and errors == 0
            passed = valid and (completed.returncode == 0 and failures == 0 if name == 'control'
                                else completed.returncode != 0 and failures > 0)
            evidence.append({'operator': name, 'tests': tests, 'assertionFailures': failures, 'errors': errors, 'verified': passed})
            if not passed:
                print(completed.stdout.decode('utf8', errors='replace')[-4000:])
                raise SystemExit(f'Mutation/control not verified: {name}; build errors are not mutation kills')
    output = ROOT / 'apps/api/target/mutation-smoke.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf8')
    print('Control passed; all three targeted retry-policy mutations were killed by assertions.')


if __name__ == '__main__':
    run()
