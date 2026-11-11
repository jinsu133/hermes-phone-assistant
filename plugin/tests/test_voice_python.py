"""Real fresh subprocesses on A36; no model, device actions or real credentials."""
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_python import run_python

class PythonIsolationTest(unittest.TestCase):
    def test_functions_strings_collections_and_preloaded_modules(self):
        result=run_python('import json, statistics\ndef twice(s): return s*2\nassert twice("가")=="가가"\nprint(json.dumps({"mean":statistics.mean([2,4,6])}))')
        self.assertTrue(result['executed'],result);self.assertEqual(result['exit_code'],0,result)
        self.assertIn('"mean": 4',result['stdout'])

    def test_real_reads_writes_network_and_process_creation_denied(self):
        with tempfile.TemporaryDirectory() as d:
            source=Path(d)/'fixture.txt';source.write_text('SYNTHETIC_PRIVATE_FIXTURE')
            target=Path(d)/'output.txt'
            cases=[f'open({str(source)!r}).read()',f'open({str(target)!r},"w").write("x")','__import__("socket").socket()','__import__("os").fork()','__import__("os").execv("/system/bin/true",["true"])']
            for operation in cases:
                with self.subTest(operation=operation):
                    r=run_python('try:\n '+operation+'\n print("UNEXPECTED")\nexcept (PermissionError,OSError):\n print("DENIED")')
                    self.assertTrue(r['executed'],r);self.assertIn('DENIED',r['stdout']);self.assertNotIn('UNEXPECTED',r['stdout'])
            self.assertFalse(target.exists());self.assertEqual(source.read_text(),'SYNTHETIC_PRIVATE_FIXTURE')

    def test_environment_and_descriptor_scrubbing(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ,{'VOICE_FAKE_SECRET':'SYNTHETIC_MUST_NOT_INHERIT'}):
            r=run_python('import os\nassert "VOICE_FAKE_SECRET" not in os.environ\nprint("SCRUBBED")')
        self.assertEqual(r['exit_code'],0,r);self.assertIn('SCRUBBED',r['stdout'])

    def test_output_and_cpu_are_bounded(self):
        r=run_python('while True: print("x"*1000,flush=True)')
        self.assertEqual(r['stop_reason'],'output_limit',r)
        self.assertLessEqual(len(r['stdout'].encode()),32768)
        r=run_python('while True: pass')
        self.assertNotEqual(r['exit_code'],0,r)

    def test_memory_growth_and_inherited_descriptor_are_denied(self):
        import os
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'fixture';path.write_text('SYNTHETIC_DESCRIPTOR_FIXTURE')
            fd=os.open(path,os.O_RDONLY);os.set_inheritable(fd,True)
            try:
                r=run_python(f'import os\ntry: os.read({fd},100); print("LEAK")\nexcept OSError: print("CLOSED")')
                self.assertIn('CLOSED',r['stdout']);self.assertNotIn('LEAK',r['stdout'])
            finally:os.close(fd)
        r=run_python('data=bytearray(128*1024*1024); print("UNBOUNDED")')
        self.assertTrue(r['executed']);self.assertNotEqual(r['exit_code'],0);self.assertNotIn('UNBOUNDED',r['stdout'])

    def test_cancellation_during_execution_reaps_child(self):
        import threading,time
        cancel=threading.Event();timer=threading.Timer(.25,cancel.set);timer.start()
        try:r=run_python('while True: pass',cancel=cancel)
        finally:timer.cancel()
        self.assertEqual(r['stop_reason'],'cancelled',r);self.assertIsNotNone(r['exit_code'])

    def test_cancel_while_bootstrap_is_stalled_does_not_block_stdin(self):
        import subprocess,threading,time
        from unittest import mock
        original=subprocess.Popen
        def stalled(command,**kw):
            return original([sys.executable,'-I','-S','-c','import time; time.sleep(30)'],**kw)
        cancel=threading.Event();timer=threading.Timer(.2,cancel.set);timer.start()
        try:
            with mock.patch('voice_python.subprocess.Popen',side_effect=stalled):r=run_python('x'*8192,cancel=cancel)
        finally:timer.cancel()
        self.assertFalse(r['executed']);self.assertEqual(r['stop_reason'],'cancelled',r)

    def test_cancelled_before_start_and_oversize_never_execute(self):
        import threading
        cancel=threading.Event();cancel.set()
        self.assertFalse(run_python('print(123)',cancel=cancel)['executed'])
        with self.assertRaises(ValueError):run_python('x'*8193)

if __name__=='__main__':unittest.main()
