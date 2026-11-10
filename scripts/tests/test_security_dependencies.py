import unittest
from unittest.mock import patch

from scripts.security_dependencies import resolved_packages, scan


class DependencySecurityTests(unittest.TestCase):
    def test_resolved_runtime_and_classifier_coordinates_are_scanned(self):
        result = resolved_packages("   org.test:core:jar:1.2.3:compile\n   org.test:other:jar:tests:2.3.4:test -- module")
        self.assertEqual([p['version'] for p in result], ['1.2.3', '2.3.4'])

    def test_empty_or_malformed_input_cannot_pass(self):
        with self.assertRaises(ValueError):
            resolved_packages('build failed')

    @patch('scripts.security_dependencies.request_json', return_value={'results': []})
    def test_incomplete_response_cannot_pass(self, _request):
        with self.assertRaises(ValueError):
            scan(resolved_packages('   org.test:core:jar:1.2.3:compile'))

    @patch('scripts.security_dependencies.request_json')
    def test_advisory_severity_is_retained_for_the_gate(self, request):
        request.side_effect = [{'results': [{'vulns': [{'id': 'GHSA-test-only'}]}]},
                               {'database_specific': {'severity': 'HIGH'}}]
        result = scan(resolved_packages('   org.test:core:jar:1.2.3:compile'))
        self.assertEqual(result['findings'][0]['severity'], 'HIGH')
