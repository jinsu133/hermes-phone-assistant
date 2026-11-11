import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('voice_tool_policy',Path(__file__).resolve().parents[1]/'voice_tool_policy.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ToolPolicyTests(unittest.TestCase):
 def test_arithmetic_only(self):
  self.assertTrue(m.arithmetic_command({'command':"python -I -S -c 'print((12.5*4+10)/3)'"}))
  for cmd in ["python -c 'print(1)'","python -I -S -c 'print(__import__(\"os\").getcwd())'","python -I -S -c 'print(9**999999)'","python -I -S -c 'print(open(\"secret\").read())'","python -I -S -c 'print(1);print(2)'","python -I -S -c 'print(1)' ; id"]:
   self.assertFalse(m.arithmetic_command({'command':cmd}),cmd)
 def test_new_files_only(self):
  with tempfile.TemporaryDirectory() as tmp:
   home=Path(tmp); root=m.workspace(home,'request');root.mkdir(parents=True)
   path=root/'answer.md'; args={'path':str(path),'content':'new'}
   self.assertTrue(m.file_allowed('write_file',args,root))
   path.write_text('existing')
   self.assertFalse(m.file_allowed('write_file',args,root));self.assertTrue(m.file_allowed('read_file',{'path':str(path)},root))
   self.assertFalse(m.file_allowed('read_file',{'path':str(home/'secret')},root))
   link=root/'link.md';link.symlink_to(home/'elsewhere.md')
   self.assertFalse(m.file_allowed('write_file',{'path':str(link),'content':'x'},root))
 def test_cross_request_and_hidden(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=m.workspace(Path(tmp),'one')
   for name in ['../two/result.md','.env','.keys/result.md']:
    self.assertFalse(m.file_allowed('write_file',{'path':str(root/name),'content':'x'},root))
if __name__=='__main__': unittest.main()
