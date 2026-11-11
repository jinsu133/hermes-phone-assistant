"""Offline source-only contract acceptance; no production registration."""
import json
from pathlib import Path
import subprocess
import sys
import unittest

PROBE = Path(__file__).with_name('probe_voice_task_continuity.py')

class Contract(unittest.TestCase):
    def test_public_stock_contract_matrix(self):
        result = subprocess.run([sys.executable, '-B', str(PROBE)], capture_output=True,
                                text=True, timeout=580)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        records = [json.loads(line[7:]) for line in result.stdout.splitlines()
                   if line.startswith('RESULT ')]
        self.assertEqual({r['case'] for r in records}, {
            'success', 'middleware_raise', 'middleware_omit', 'final_missing_state',
            'playback_missing', 'playback_failed', 'stale_ack', 'missing_provider',
            'duplicate_open', 'unknown_effect', 'state_controls', 'final_missing_after_read',
            'observer_raise', 'hook_disposed', 'duplicate_ack'})
        self.assertTrue(all(r['accepted'] for r in records))
        print(result.stdout)

if __name__ == '__main__':
    unittest.main()
