"""Discover configuration/security regressions and reject a vacuous zero-test success."""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
suite = unittest.defaultTestLoader.discover(str(ROOT / 'scripts/tests'), pattern='test_*.py')
count = suite.countTestCases()
if count == 0:
    raise SystemExit('No configuration/security tests discovered; refusing a vacuous pass')
print(f'Discovered {count} configuration/security tests.', flush=True)
result = unittest.TextTestRunner(verbosity=2).run(suite)
raise SystemExit(0 if result.wasSuccessful() else 1)
