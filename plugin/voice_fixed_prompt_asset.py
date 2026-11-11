"""Private, explicit one-attempt acceptance PCM asset; never runtime write-through.
No credentials, provider bodies, wire scope, receipts or approval authority on disk.
Caller trusts profile home and wrapper code; same-UID malicious writers are out of scope.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat

from .voice_fixed_prompt_cache import CACHE_VERSION, PCM_FORMAT
from .voice_pcm_stream import Limits, vertex_pcm

ACCEPTANCE_TEXT = '에이전트가 답변을 준비하고 있습니다.'
MAX_PCM_BYTES = 480000
MANIFEST_KEYS = {'version', 'key_sha256', 'pcm_sha256', 'pcm_bytes', 'format'}


def key_digest(identity):
    key = (CACHE_VERSION, ACCEPTANCE_TEXT, *identity, PCM_FORMAT)
    return hashlib.sha256(json.dumps(key, ensure_ascii=True, separators=(',', ':')).encode()).hexdigest()


class PrivatePromptAsset:
    def __init__(self, home):
        self.root = Path(home)/'cache/voice-fixed-prompts-v1'

    def _directory(self, create=False):
        if create:
            self.root.mkdir(parents=True, mode=0o700, exist_ok=True)
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            os.close(fd)
            raise ValueError('asset_directory_private')
        return fd

    @staticmethod
    def _read(directory, name, limit):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, 'rb') as source:
            info = os.fstat(source.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1
                    or not 0 < info.st_size <= limit):
                raise ValueError('asset_file_private_or_bounds')
            data = source.read(limit+1)
            if len(data) != info.st_size or len(data) > limit:
                raise ValueError('asset_file_bounds')
            return data

    @staticmethod
    def _write(directory, name, data, exclusive=False):
        temporary = name if exclusive else '.pending-'+secrets.token_hex(16)
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=directory)
        try:
            with os.fdopen(fd, 'wb') as target:
                target.write(data)
                target.flush()
                os.fsync(target.fileno())
            if not exclusive:
                os.replace(temporary, name, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
        finally:
            if not exclusive:
                try: os.unlink(temporary, dir_fd=directory)
                except FileNotFoundError: pass

    def load(self, identity):
        """Validate disk afresh. A RAM hit never makes pregenerated readiness true."""
        directory = None
        try:
            directory = self._directory()
            metadata = json.loads(self._read(directory, 'manifest.json', 2048))
            if (type(metadata) is not dict or set(metadata) != MANIFEST_KEYS
                    or metadata['version'] != CACHE_VERSION
                    or metadata['key_sha256'] != key_digest(identity)
                    or metadata['format'] != PCM_FORMAT
                    or type(metadata['pcm_bytes']) is not int):
                return None
            pcm = self._read(directory, 'acceptance.pcm', MAX_PCM_BYTES)
            if (len(pcm) % 2 or len(pcm) != metadata['pcm_bytes']
                    or hashlib.sha256(pcm).hexdigest() != metadata['pcm_sha256']):
                return None
            return pcm
        except (OSError, ValueError, TypeError, RecursionError):
            return None
        finally:
            if directory is not None: os.close(directory)

    def generate_once(self, resolve, *, authorized=False):
        """Explicit API only. Reserve before wrapper/auth/provider dispatch; no retry.
        resolve() -> (exact identity, zero-argument raw Vertex SSE opener).
        Parser enforces PCM/wire/time bounds, STOP, EOF and successful source close.
        Transport has bounded reads; wrapper authentication is not hard-time-bounded.
        """
        if authorized is not True:
            return {'status': 'not_authorized', 'ready': False}
        directory = None
        try:
            directory = self._directory(create=True)
            self._write(directory, 'attempt.json', b'{"status":"reserved","attempts":1}', exclusive=True)
        except FileExistsError:
            return {'status': 'attempt_already_reserved', 'ready': False}
        except Exception:
            return {'status': 'reservation_failed', 'ready': False}
        finally:
            if directory is not None: os.close(directory)
        result = {'status': 'generation_failed', 'ready': False}
        try:
            identity, opener = resolve()
            limits = Limits(pcm_bytes=MAX_PCM_BYTES, wire_bytes=2*1024*1024, seconds=60)
            # One invocation, no cache lookup, no playback and no retry.
            pcm = b''.join(vertex_pcm(opener(), limits=limits))
            metadata = {'version': CACHE_VERSION, 'key_sha256': key_digest(identity),
                        'pcm_sha256': hashlib.sha256(pcm).hexdigest(),
                        'pcm_bytes': len(pcm), 'format': PCM_FORMAT}
            directory = self._directory()
            try:
                self._write(directory, 'acceptance.pcm', pcm)
                self._write(directory, 'manifest.json', json.dumps(metadata, sort_keys=True).encode())
            finally:
                os.close(directory)
            if self.load(identity) == pcm:
                result = dict(metadata, status='ready', ready=True)
        except BaseException:
            # Never retain exception text/type/traceback, provider body or credentials.
            # A crash/interrupt still leaves the durable reservation consumed.
            pass
        try:
            directory = self._directory()
            try: self._write(directory, 'result.json', json.dumps(result, sort_keys=True).encode())
            finally: os.close(directory)
        except Exception:
            return {'status': 'status_write_failed', 'ready': False}
        return result
