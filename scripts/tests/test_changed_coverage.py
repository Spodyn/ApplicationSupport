import unittest
from scripts.testing.changed_coverage import uncovered_changes


class ChangedCoverageTests(unittest.TestCase):
    def test_new_executable_line_requires_hits_but_comments_and_unscoped_ui_are_not_counted(self):
        diff = '+++ b/apps/web/lib/security/file.ts\n@@ -1,0 +2,2 @@\n+code\n+comment\n'
        lcov = 'SF:lib/security/file.ts\nDA:2,0\n'
        self.assertEqual(uncovered_changes(diff, lcov), ['apps/web/lib/security/file.ts:2'])
        self.assertEqual(uncovered_changes(diff, lcov.replace('2,0', '2,1')), [])

    def test_deletions_do_not_shift_added_line_numbers(self):
        diff = '+++ b/apps/web/a.ts\n@@ -3,2 +3,1 @@\n-old\n-old2\n+new\n'
        self.assertEqual(uncovered_changes(diff, 'SF:a.ts\nDA:3,0\n'), ['apps/web/a.ts:3'])
