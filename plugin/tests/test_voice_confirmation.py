import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('voice_confirmation',Path(__file__).resolve().parents[1]/'voice_confirmation.py')
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class ConfirmationTests(unittest.TestCase):
    def setUp(self):
        self.now=100.0
        self.b=m.VoiceConfirmation(lambda:self.now)
        self.b.begin('request','action','내용 확인','이 메시지 보내기 허용','a'*64)
    def arm(self): return self.b.arm('request','action')['nonce']
    def test_no_text_or_early_approval(self):
        with self.assertRaises(ValueError): self.b.finish_audio('request','action','이 메시지 보내기 허용')
    def test_exact_fresh_once(self):
        nonce=self.arm(); self.b.claim_audio('request','action',nonce)
        self.assertTrue(self.b.finish_audio('request','action','이 메시지 보내기 허용.')['allowed'])
        with self.assertRaises(ValueError): self.b.claim_audio('request','action',nonce)
    def test_wrong_request_and_action(self):
        nonce=self.arm()
        for request,action in [('other','action'),('request','other')]:
            with self.assertRaises(ValueError): self.b.claim_audio(request,action,nonce)
    def test_tts_and_old_nonce(self):
        with self.assertRaises(ValueError): self.b.claim_audio('request','action','old')
        self.arm()
        with self.assertRaises(ValueError): self.b.claim_audio('request','action','old')
    def test_no_rearm(self):
        self.arm()
        with self.assertRaises(ValueError): self.arm()
    def test_denials(self):
        for text in ['네','좋아요','','보내지 마','이 메시지 보내기 허용 취소','이 메시지 보내기 허용하지 마','이 메시지 보내기 허용이라고 말하면 돼요']:
            self.b.clear(); self.b.begin('request','action','내용','이 메시지 보내기 허용','a'*64)
            self.b.claim_audio('request','action',self.arm())
            self.assertFalse(self.b.finish_audio('request','action',text)['allowed'])
    def test_expiry(self):
        nonce=self.arm(); self.now+=241
        with self.assertRaises(ValueError): self.b.claim_audio('request','action',nonce)
    def test_cancel_during_stt(self):
        self.b.claim_audio('request','action',self.arm()); self.b.clear()
        with self.assertRaises(ValueError): self.b.finish_audio('request','action','이 메시지 보내기 허용')
    def test_no_new_pending_overwrite(self):
        with self.assertRaises(ValueError): self.b.begin('r2','a2','prompt','phrase','b'*64)

if __name__=='__main__': unittest.main()
