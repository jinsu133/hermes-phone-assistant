import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_documents import VoiceDocuments
from voice_drafts import DraftWorkspace
from voice_revision import prepare_revision, save_revision


class RevisionTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.path=self.root/'source.md'
        self.source='회의 시간은 오전 9시입니다.\n장소는 회의실입니다.'
        self.path.write_text(self.source)
        self.docs=VoiceDocuments({'meeting':str(self.path)})
        self.drafts=DraftWorkspace(self.root)
        self.args=dict(action='revise_document',name='meeting',expected_sha256=hashlib.sha256(self.path.read_bytes()).hexdigest(),old_text='오전 9시',new_text='오전 10시')

    def stage(self): return prepare_revision(self.docs,'owner',self.args)
    def receipt(self,stage): return dict(state='completed',dispatched=True,permission_only=True,voice_request_id='owner',authorized_digest=stage.action_digest)

    def test_explicit_receipt_saves_verified_copy_and_preserves_original(self):
        stage=self.stage()
        self.assertFalse((self.root/'voice-workspaces').exists())
        result=save_revision(self.docs,self.drafts,stage,self.receipt(stage))
        self.assertTrue(result['verified']);self.assertTrue(result['original_preserved'])
        self.assertEqual(self.path.read_text(),self.source)
        self.assertIn('오전 10시',Path(result['path']).read_text())
        with self.assertRaises(ValueError): save_revision(self.docs,self.drafts,stage,self.receipt(stage))

    def test_receipt_must_bind_request_digest_and_native_permission(self):
        stage=self.stage()
        for change in ({'state':'cancelled'},{'voice_request_id':'other'}, {'authorized_digest':'0'*64}, {'permission_only':False},{'dispatched':False}):
            receipt=self.receipt(stage);receipt.update(change)
            with self.assertRaises(ValueError):save_revision(self.docs,self.drafts,stage,receipt)
        self.assertFalse((self.root/'voice-workspaces').exists())

    def test_source_changed_after_prompt_is_rejected(self):
        stage=self.stage();self.path.write_text('다른 문서')
        with self.assertRaisesRegex(ValueError,'document_changed'):save_revision(self.docs,self.drafts,stage,self.receipt(stage))
        self.assertFalse((self.root/'voice-workspaces').exists())

    def test_unique_match_and_digest_required(self):
        for update in ({'expected_sha256':'0'*64},{'old_text':'없는 문구'},{'old_text':'입니다'},{'old_text':''},{'new_text':'x'*161},{'approved':True}):
            args=dict(self.args,**update)
            with self.assertRaises(ValueError):prepare_revision(self.docs,'owner',args)

    def test_symlink_change_rejected(self):
        stage=self.stage();other=self.root/'other.md';other.write_text(self.source)
        self.path.unlink();self.path.symlink_to(other)
        with self.assertRaises(OSError):save_revision(self.docs,self.drafts,stage,self.receipt(stage))

    def test_frozen_payload_and_different_changes_have_different_digest(self):
        from dataclasses import FrozenInstanceError
        stage=self.stage()
        with self.assertRaises(FrozenInstanceError):stage.new='다른 값'
        other=prepare_revision(self.docs,'owner',dict(self.args,new_text='오후 2시'))
        self.assertNotEqual(stage.action_digest,other.action_digest)


if __name__=='__main__':unittest.main()
