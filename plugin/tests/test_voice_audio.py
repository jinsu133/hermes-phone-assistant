import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('voice_audio', ROOT/'voice_audio.py')
audio = importlib.util.module_from_spec(spec); spec.loader.exec_module(audio)


class AudioPartsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.one = self.root/'one.mp3'; self.one.write_bytes(b'part-one')
        self.two = self.root/'two.wav'; self.two.write_bytes(b'part-two')

    def test_order_and_formats_are_preserved(self):
        self.assertEqual(audio.read_segments(self.root,[str(self.two),str(self.one)],16),
                         [(b'part-two','.wav'),(b'part-one','.mp3')])

    def test_total_budget_not_per_file(self):
        with self.assertRaisesRegex(ValueError,'tts_audio_too_large'):
            audio.read_segments(self.root,[str(self.one),str(self.two)],15)

    def test_duplicate_output_cannot_repeat_speech(self):
        with self.assertRaises(ValueError):
            audio.read_segments(self.root,[str(self.one),str(self.one)],100)

    def test_outside_paths_and_traversal_are_denied(self):
        for path in ('/not-allowed.mp3', str(self.root/'..'/'one.mp3'), 'one.mp3'):
            with self.assertRaises(ValueError): audio.read_segments(self.root,[path],100)

    def test_symlink_and_parent_symlink_are_denied(self):
        sym=self.root/'alias.mp3';sym.symlink_to(self.one)
        with self.assertRaises(OSError): audio.read_segments(self.root,[str(sym)],100)
        actual=self.root/'actual';actual.mkdir();(actual/'audio.mp3').write_bytes(b'fixture')
        link=self.root/'linked';link.symlink_to(actual,target_is_directory=True)
        with self.assertRaises(OSError): audio.read_segments(self.root,[str(link/'audio.mp3')],100)

    def test_empty_and_nonregular_output_are_denied(self):
        self.one.write_bytes(b'')
        with self.assertRaises(ValueError): audio.read_segments(self.root,[str(self.one)],100)
        self.one.unlink();os.mkfifo(self.one)
        with self.assertRaises(ValueError): audio.read_segments(self.root,[str(self.one)],100)

    def test_invalid_list_shapes_are_denied(self):
        for paths in ([],None,'file.mp3',[None],[str(self.one)]*17):
            with self.assertRaises(ValueError): audio.read_segments(self.root,paths,100)

    def test_legacy_never_silently_returns_first_part_only(self):
        parts=[(b'one','.mp3'),(b'two','.wav')]
        self.assertEqual(audio.cached_segment(parts,0,4)[0],409)
        self.assertEqual(audio.cached_segment(parts,0,5),(200,b'one','.mp3'))
        self.assertEqual(audio.cached_segment(parts,1,5),(200,b'two','.wav'))
        self.assertEqual(audio.cached_segment(parts,2,5)[0],400)


if __name__ == '__main__': unittest.main()
