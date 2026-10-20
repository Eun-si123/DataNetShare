import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('agent_ops', Path(__file__).with_name('agent-ops.py'))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class AgentOpsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = (ops.ROOT, ops.CONFIG, ops.RECEIPTS)
        ops.ROOT = self.root
        ops.CONFIG = self.root / '.github' / 'agent-areas.json'
        ops.RECEIPTS = self.root / '.github' / 'agent-inspections'
        self.addCleanup(self.restore)
        (self.root / '.github').mkdir()
        (self.root / 'src').mkdir()
        (self.root / 'src' / 'main.py').write_text('x = 1  # TODO investigate\n')
        ops.CONFIG.write_text(json.dumps({'schema': 1, 'repository': 'example/repo', 'areas': {'main-code': ['src']}}))
        for cmd in (('init', '-b', 'main'), ('add', '.'),
                    ('-c', 'user.name=Tester', '-c', 'user.email=t@example.invalid', 'commit', '-m', 'base')):
            subprocess.run(['git', '-C', str(self.root), *cmd], check=True, capture_output=True)
        subprocess.run(['git', '-C', str(self.root), 'remote', 'add', 'origin', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'update-ref', 'refs/remotes/origin/main', ops.git('rev-parse', 'HEAD')], check=True)

    def restore(self):
        ops.ROOT, ops.CONFIG, ops.RECEIPTS = self.old

    def test_scan_unreviewed_and_no_remote_authority(self):
        result = ops.scan()
        self.assertFalse(result['remote_verified'])
        self.assertEqual(result['areas'][0]['status'], 'NEEDS_REINSPECTION')
        self.assertEqual(result['areas'][0]['hints'][0]['kind'], 'TODO')

    def test_receipt_becomes_stale_after_code_change(self):
        result = ops.receipt('main-code', 'Inspected src/main.py; unit checks run')
        self.assertEqual(result['receipt']['area'], 'main-code')
        self.assertEqual(ops.scan()['areas'][0]['status'], 'REVIEWED_UNCHANGED')
        (self.root / 'src' / 'main.py').write_text('x = 2\n')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Tester', '-c', 'user.email=t@example.invalid', 'commit', '-m', 'next'], check=True, capture_output=True)
        row = ops.scan()['areas'][0]
        self.assertEqual(row['status'], 'NEEDS_REINSPECTION')
        self.assertEqual(row['changes_since_review'], 1)

    def test_no_receipt_on_dirty_worktree(self):
        (self.root / 'src' / 'main.py').write_text('changed\n')
        with self.assertRaisesRegex(RuntimeError, 'dirty worktree'):
            ops.receipt('main-code', 'Unverified claim')

    def test_review_flags_missing_test_and_whitespace(self):
        (self.root / 'src' / 'main.py').write_text('x = 2  \n')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Tester', '-c', 'user.email=t@example.invalid', 'commit', '-m', 'bad whitespace'], check=True, capture_output=True)
        report = ops.independent_review('origin/main')
        self.assertFalse(report['diff_check_pass'])
        self.assertTrue(report['warnings'])

    def test_remote_occupied_area_blocks_preflight(self):
        old = ops.git('rev-parse', 'origin/main')
        with patch.object(ops, 'api', return_value={'commit': {'sha': old}}), \
             patch.object(ops, 'open_prs', return_value=[{'number': 12, 'head': {'ref': 'agent/other/task'}}]), \
             patch.object(ops, 'pr_areas', return_value={'main-code': [{'number': 12, 'branch': 'agent/other/task'}]}):
            result = ops.preflight('main-code', 'agent/main-code/new-work', None)
        self.assertEqual(result['result'], 'CONFLICT_OR_REVIEW_REQUIRED')

    def test_own_branch_is_not_foreign_lease(self):
        old = ops.git('rev-parse', 'origin/main')
        with patch.object(ops, 'api', return_value={'commit': {'sha': old}}), \
             patch.object(ops, 'open_prs', return_value=[{'number': 12, 'head': {'ref': 'agent/main-code/my-task'}}]), \
             patch.object(ops, 'pr_areas', return_value={'main-code': [{'number': 12, 'branch': 'agent/main-code/my-task'}]}):
            result = ops.preflight('main-code', 'agent/main-code/my-task', None)
        self.assertEqual(result['result'], 'NO_CONFLICT_OBSERVED_NOT_A_LOCK')

    def test_test_file_names_are_recognized(self):
        self.assertIsNotNone(ops.TEST_NAME.search('scripts/test_agent_ops.py'))
        self.assertIsNotNone(ops.TEST_NAME.search('android/app/src/test/java/Test.kt'))
        self.assertIsNotNone(ops.TEST_NAME.search('launcher/LauncherTests.cs'))

    def test_unknown_area_or_traversal_is_rejected(self):
        with self.assertRaises(ValueError):
            ops.receipt('../bad', 'evidence')
        ops.CONFIG.write_text(json.dumps({'schema': 1, 'repository': 'example/repo', 'areas': {'main-code': ['../secrets']}}))
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            ops.scan()


if __name__ == '__main__':
    unittest.main()
