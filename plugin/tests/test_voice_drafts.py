import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('voice_drafts',Path(__file__).resolve().parents[1]/'voice_drafts.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class DraftTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
  self.home=Path(self.temp.name); self.d=m.DraftWorkspace(self.home)
 def test_create_read_revise_and_check(self):
  one=self.d.perform('one',{'action':'write','name':'demo.py','content':'answer = 3 + 4\n'})
  self.assertTrue(one['verified']); self.assertEqual(self.d.perform('one',{'action':'read','name':'demo.py'})['content'],'answer = 3 + 4\n')
  with self.assertRaises(ValueError): self.d.perform('one',{'action':'write','name':'demo.py','content':'changed'})
  two=self.d.perform('one',{'action':'write','name':'demo.py','content':'answer = 8\n','expected_sha256':one['sha256']})
  self.assertNotEqual(one['sha256'],two['sha256'])
  self.assertTrue(self.d.perform('one',{'action':'check','name':'demo.py'})['valid'])
  self.assertEqual(self.d.perform('one',{'action':'list'})['files'],['demo.py'])
 def test_no_execution_and_invalid_syntax(self):
  target=self.home/'not-created'
  self.d.perform('one',{'action':'write','name':'demo.py','content':f'open({str(target)!r}, "w").write("must not execute")'})
  self.assertTrue(self.d.perform('one',{'action':'check','name':'demo.py'})['valid']); self.assertFalse(target.exists())
  self.d.perform('one',{'action':'write','name':'broken.py','content':'def (: bad'})
  self.assertFalse(self.d.perform('one',{'action':'check','name':'broken.py'})['valid'])
 def test_owner_digest_and_links(self):
  one=self.d.perform('one',{'action':'write','name':'report.md','content':'draft'})
  with self.assertRaises(ValueError): self.d.perform('two',{'action':'read','name':'report.md'})
  file=self.home/'voice-workspaces'/'one'/'report.md'; file.write_text('external change')
  with self.assertRaises(ValueError): self.d.perform('one',{'action':'write','name':'report.md','content':'oops','expected_sha256':one['sha256']})
  secret=self.home/'outside.md';secret.write_text('private')
  link=file.parent/'link.md'; link.symlink_to(secret)
  with self.assertRaises((ValueError,OSError)): self.d.perform('one',{'action':'write','name':'link.md','content':'oops'})
  # Android SELinux prohibits creating hard links here. Exercise the descriptor guard
  # with a synthetic stat result; this does not claim a real hard-link E2E test.
  from unittest import mock
  from types import SimpleNamespace
  import stat
  with mock.patch.object(m.os,'fstat',return_value=SimpleNamespace(st_mode=stat.S_IFREG,st_nlink=2,st_size=1)):
   with self.assertRaises(ValueError): self.d._read(-1)
  for name in ['../outside.md','.env','nested/file.md','x.sh']:
   with self.assertRaises(ValueError): self.d.perform('one',{'action':'write','name':name,'content':'x'})
  self.assertEqual(secret.read_text(),'private')
 def test_only_own_files_after_restart_and_unknown_args(self):
  self.d.perform('one',{'action':'write','name':'report.md','content':'draft'})
  with self.assertRaises(ValueError): m.DraftWorkspace(self.home).perform('one',{'action':'read','name':'report.md'})
  with self.assertRaises(ValueError): self.d.perform('one',{'action':'list','request_id':'other'})
 def test_numeric_program_runs_assertions_without_python_execution(self):
  self.d.perform('one',{'action':'write','name':'sum.py','content':'total = 0\nfor i in range(1, 6):\n    total = total + i\nassert total == 15\nprint(total)\n'})
  result=self.d.perform('one',{'action':'run_numeric','name':'sum.py'})
  self.assertTrue(result['success']);self.assertTrue(result['executed'])
  self.assertEqual(result['stdout'],'15');self.assertEqual(result['assertions_passed'],1)
 def test_numeric_refuses_effects_and_bounds(self):
  for text in ['import os','open("x", "w")','print((1).__class__)','while True: pass',
               'for i in range(1001): print(i)','assert 1 == 2','print(1/0)','print("x"*1000)',
               'for i in range(1000):\n for j in range(1000): x=1']:
   result=m.NumericProgram().run(text)
   self.assertFalse(result['success'],text);self.assertLessEqual(result['steps'],5001)
 def test_json_validation(self):
  self.d.perform('one',{'action':'write','name':'data.json','content':'{"a":1}'})
  self.assertTrue(self.d.perform('one',{'action':'check','name':'data.json'})['valid'])
if __name__=='__main__': unittest.main()
