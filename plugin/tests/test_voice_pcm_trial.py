"""Authenticated exact synthetic trial routes, no provider/device IO."""
import json
import unittest
from test_voice_pcm_bridge import Fixture


class TrialHTTPTest(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.f.bridge._trial_enabled = True  # test-only init, no network switch exists
    def tearDown(self):
        self.f.close()
    def ready(self):
        f = self.f
        code, hello = f.post('/connect', dict(profile='fixture', device_epoch='device-process-A'))
        self.assertEqual(code, 200)
        f.scope = {k: hello[k] for k in ('profile', 'session', 'host_epoch', 'device_epoch', 'epoch')}
        self.assertEqual(f.post('/stop-ack', dict(hello, closed=True))[0], 200)
        code, descriptor = f.post('/trial/arm', f.scope)
        self.assertEqual(code, 200)
        self.revision = descriptor.pop('consent_revision')
        self.bound = descriptor
        f.full = {k: v for k, v in descriptor.items() if k != 'correlation_id'}
    def played(self):
        f = self.f
        self.assertEqual(f.post('/stream', f.full)[0], 200)
        receipt = dict(self.bound, frame_count=16, status='played')
        code, accepted = f.post('/trial/receipt', receipt)
        self.assertEqual(code, 200)
        self.assertEqual(accepted['correlation_id'], self.bound['correlation_id'])
        return dict(self.bound, listening_ticket=accepted['listening_ticket'], consent_revision=self.revision)
    def test_consumed_after_eos_cleanup_is_one_use_not_played(self):
        self.ready()
        self.assertEqual(self.f.post('/stream', self.f.full)[0], 200)
        with self.f.bridge._lock:
            self.assertTrue(self.f.bridge._completed)
            self.assertIsNone(self.f.bridge._active)
        self.assertEqual(self.f.post('/trial/consumed', self.bound)[0], 200)
        self.assertEqual(self.f.post('/trial/consumed', self.bound)[0], 409)
        self.assertIsNone(self.f.bridge._ticket)
        self.assertEqual(self.f.bridge.trial_effects, 0)

    def test_consumed_before_stream_is_denied(self):
        self.ready()
        self.assertEqual(self.f.post('/trial/consumed', self.bound)[0], 409)
        self.assertFalse(self.f.bridge.trial_consumed.is_set())

    def test_late_consumed_after_cancel_is_denied(self):
        self.ready()
        self.assertEqual(self.f.post('/stream', self.f.full)[0], 200)
        self.assertEqual(self.f.post('/cancel', self.f.scope)[0], 200)
        self.assertEqual(self.f.post('/trial/consumed', self.bound)[0], 409)
        self.assertEqual(self.f.bridge.trial_effects, 0)

    def test_default_refuses_trial_and_auth_precedes_schema(self):
        self.f.bridge._trial_enabled = False
        for path in ('/trial/arm', '/trial/consumed', '/trial/receipt', '/trial/listening'):
            self.assertEqual(self.f.post(path, b'not-json', token='wrong')[0], 401)
            self.assertEqual(self.f.post(path, {})[0], 404)
        self.assertFalse(self.f.sources)
    def test_arm_no_source_one_use_unknown_authority_fields_denied(self):
        self.ready()
        self.assertEqual(self.f.post('/trial/arm', self.f.scope)[0], 409)
        for k, v in [('approved', True), ('effect', 'shell'), ('url', 'https://example.org'), ('text', 'speech')]:
            self.assertEqual(self.f.post('/trial/arm', dict(self.f.scope, **{k: v}))[0], 400)
        self.assertEqual(self.f.sources, [])
    def test_ticket_one_use_echo_and_no_sensitive_approval(self):
        self.ready(); pending = self.played()
        self.assertEqual(self.f.post('/trial/listening', dict(pending, approved=True))[0], 400)
        self.assertEqual(self.f.post('/trial/listening', pending)[0], 200)
        self.assertEqual(self.f.bridge.trial_effects, 1)
        self.assertEqual(self.f.post('/trial/listening', pending)[0], 409)
        self.assertEqual(self.f.bridge.trial_effects, 1)
        self.assertIsNone(self.f.bridge._ticket)
    def test_every_scope_field_and_correlation_must_match(self):
        self.ready(); pending = self.played()
        for k in self.bound:
            wrong = dict(pending)
            wrong[k] = wrong[k] + 1 if type(wrong[k]) is int else wrong[k] + 'x'
            self.assertIn(self.f.post('/trial/listening', wrong)[0], (403, 409), k)
        for k in ('epoch', 'generation', 'consent_revision'):
            wrong = dict(pending); wrong[k] = True
            self.assertEqual(self.f.post('/trial/listening', wrong)[0], 409)
        self.assertEqual(self.f.bridge.trial_effects, 0)
        self.assertEqual(self.f.post('/trial/listening', pending)[0], 200)
    def test_unconsumed_ticket_off_revoked(self):
        self.ready(); pending = self.played()
        self.assertEqual(self.f.post('/cancel', self.f.scope)[0], 200)
        self.assertEqual(self.f.post('/trial/listening', pending)[0], 409)
        self.assertEqual(self.f.bridge.trial_effects, 0)
        self.assertIsNone(self.f.bridge._ticket)
    def test_consent_change_clears_pending_ticket(self):
        self.ready(); pending = self.played()
        self.f.bridge.set_consent(False); self.f.bridge.set_consent(True)
        self.assertEqual(self.f.post('/trial/listening', pending)[0], 409)
        self.assertEqual(self.f.bridge.trial_effects, 0)
    def test_expiry_at_effect_has_exact_expiry_or_revoked_code(self):
        self.ready(); pending = self.played()
        with self.f.bridge._lock:
            self.f.bridge._lease_deadline = -1
        result = self.f.post('/trial/listening', pending)
        self.assertIn(result, [(410, {'error': 'lease_expired'}), (409, {'error': 'revoked'})])
        self.assertEqual(self.f.bridge.trial_effects, 0)
        self.assertIsNone(self.f.bridge._ticket)
    def test_receipt_replay_and_reconnect_old_ticket(self):
        self.ready(); pending = self.played()
        self.assertEqual(self.f.post('/trial/receipt', dict(self.bound, frame_count=16, status='played'))[0], 409)
        old_correlation = self.bound['correlation_id']
        self.ready()
        self.assertNotEqual(old_correlation, self.bound['correlation_id'])
        self.assertEqual(self.f.post('/trial/listening', pending)[0], 409)
        self.assertEqual(self.f.bridge.trial_effects, 0)
    def test_correlation_never_logs_unauthenticated_attacker_value(self):
        self.ready()
        self.f.post('/trial/listening', {'correlation_id': 'attacker-secret'}, token='wrong')
        self.f.post('/trial/listening', dict(self.bound, correlation_id='attacker-secret', listening_ticket='secret', consent_revision=1))
        trace = self.f.bridge.diagnostics.export_json()
        self.assertNotIn('attacker-secret', trace)
        self.assertNotIn(self.f.token, trace)
        self.assertIn(self.bound['correlation_id'], trace)

if __name__ == '__main__':
    unittest.main()
