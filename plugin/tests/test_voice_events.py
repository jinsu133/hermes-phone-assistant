import sys, unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_events import VoiceEvents
class EventsTest(unittest.TestCase):
    def setUp(self):
        self.now=0;self.events=VoiceEvents(lambda:self.now);self.events.begin('a')
    def test_request_binding_dedup_and_throttle(self):
        e=self.events.get('a');self.assertEqual(e['kind'],'accepted')
        self.assertIsNone(self.events.get('b'));self.assertIsNone(self.events.get('a',e['seq']))
        self.assertIsNone(self.events.emit('a','phone'))
        self.now=11;self.assertIsNotNone(self.events.emit('a','phone'))
        self.now=22;self.assertIsNone(self.events.emit('a','phone'))
        self.assertIsNone(self.events.emit('a','untrusted screen text'))
    def test_final_and_cancel_discard_old_events(self):
        self.events.finish('b');self.assertIsNotNone(self.events.get('a'))
        self.events.finish('a');self.assertIsNone(self.events.get('a'))
        self.now=30;self.assertIsNone(self.events.emit('a','research'))
        self.events.begin('b');self.assertIsNone(self.events.get('a'))
        self.events.clear();self.assertIsNone(self.events.get('b'))
    def test_bound_and_expiry(self):
        for i,k in enumerate(['phone','research','document','calculation'],1):
            self.now=i*11;self.events.emit('a',k)
        self.assertEqual(self.events.get('a')['seq'],4)
        self.now=601;self.assertIsNone(self.events.get('a'))
if __name__=='__main__':unittest.main()
