import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from voice_events import VoiceEvents


class ProgressTest(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.events = VoiceEvents(lambda: self.now)
        self.events.begin('owner')

    def receipt(self):
        return self.events.record_evidence('owner', 'web_extract')

    def payload(self, receipt):
        return dict(finding='자료의 시행일을 확인했습니다.',
                    basis='현재 적용되는 기준인지 확인해야 합니다.',
                    next_step='공식 문서의 시행일과 비교하겠습니다.', evidence_ids=[receipt])

    def test_summary_links_real_receipt_and_supersedes_generic_status(self):
        data = self.payload(self.receipt())
        result = self.events.explain('owner', data)
        self.assertTrue(result['queued'])
        event = self.events.get('owner')
        self.assertEqual(event['kind'], 'explanation')
        for key in ('finding', 'basis', 'next_step'):
            self.assertIn(data[key], event['text'])
        self.now = 11
        self.assertIsNone(self.events.emit('owner', 'research'))
        self.assertEqual(self.events.get('owner'), event)

    def test_forged_cross_owner_and_reused_evidence_are_rejected(self):
        with self.assertRaises(ValueError): self.events.explain('owner', self.payload('fake'))
        receipt = self.receipt()
        with self.assertRaises(ValueError): self.events.explain('other', self.payload(receipt))
        self.events.explain('owner', self.payload(receipt))
        self.now = 11
        with self.assertRaises(ValueError): self.events.explain('owner', self.payload(receipt))
        self.events.begin('new')
        with self.assertRaises(ValueError): self.events.explain('new', self.payload(receipt))

    def test_throttle_bound_and_final(self):
        self.events.explain('owner', self.payload(self.receipt()))
        args = self.payload(self.receipt())
        self.assertFalse(self.events.explain('owner', args)['queued'])
        self.now = 11
        self.assertTrue(self.events.explain('owner', args)['queued'])
        self.now = 22
        self.assertTrue(self.events.explain('owner', self.payload(self.receipt()))['queued'])
        self.now = 33
        self.assertFalse(self.events.explain('owner', self.payload(self.receipt()))['queued'])
        self.events.finish('owner')
        self.assertIsNone(self.events.record_evidence('owner', 'web_extract'))
        with self.assertRaises(ValueError): self.events.explain('owner', args)

    def test_bounds_control_characters_and_extra_arguments(self):
        for update in ({'finding':'x'*161}, {'finding':''}, {'basis':'\u202eevil'},
                       {'next_step':'```code```'}, {'approved':True}, {'evidence_ids':[]}):
            args = self.payload(self.receipt()); args.update(update)
            with self.assertRaises(ValueError): self.events.explain('owner', args)

    def test_receipt_ledger_is_bounded_and_redaction_failure_is_closed(self):
        from unittest import mock
        first = self.receipt()
        for _ in range(25): self.receipt()
        self.assertEqual(len(self.events.evidence),24)
        with self.assertRaises(ValueError): self.events.explain('owner',self.payload(first))
        args = self.payload(self.receipt())
        with mock.patch('agent.redact.redact_sensitive_text', side_effect=RuntimeError('fixture')):
            with self.assertRaisesRegex(ValueError,'progress_redaction_unavailable'):
                self.events.explain('owner',args)
        self.assertEqual(self.events.get('owner')['kind'],'accepted')

    def test_evidence_filter_distinguishes_reads_from_effects_and_errors(self):
        from voice_progress import evidence_result
        self.assertIsNotNone(evidence_result('web_search',{}, {'data':{'web':[{'url':'https://example.org'}]}}))
        self.assertIsNotNone(evidence_result('web_extract',{}, {'results':[{'content':'fixture','error':None}]}))
        self.assertIsNone(evidence_result('web_extract',{}, {'results':[{'content':'fixture','error':'failed'}]}))
        self.assertIsNone(evidence_result('web_extract',{}, 'not JSON'))
        self.assertIsNone(evidence_result('terminal',{}, {'content':'fixture'}))
        self.assertIsNone(evidence_result('phone_act',{'action':'open_app'}, {'ok':True,'result':{'dispatched':True}}))
        self.assertIsNone(evidence_result('voice_draft',{'action':'write'}, {'ok':True,'result':{'verified':True}}))
        self.assertIsNotNone(evidence_result('voice_draft',{'action':'read'}, {'ok':True,'result':{'content':'fixture'}}))

    def test_expiry_drops_evidence_and_speech(self):
        args = self.payload(self.receipt()); self.now = 601
        with self.assertRaises(ValueError): self.events.explain('owner', args)
        self.assertIsNone(self.events.get('owner'))


if __name__ == '__main__': unittest.main()
