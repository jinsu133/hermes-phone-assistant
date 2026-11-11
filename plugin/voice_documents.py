"""Operator-selected existing text documents, read-only and bounded.

The model receives aliases, never a directory listing or arbitrary path access.
Open each path component without following symlinks. Paging is digest-bound and
uses a redacted Unicode view; file content is data, not instructions/approval.
"""
import hashlib
import os
from pathlib import Path
import re
import stat

MAX_BYTES = 262144
PAGE_CHARS = 2000
ALIAS = re.compile(r'[A-Za-z0-9가-힣][A-Za-z0-9가-힣 _-]{0,70}')
SENSITIVE = re.compile(r'(^|[_. -])(credentials?|secrets?|passwords?|tokens?|private[_. -]?keys?|api[_. -]?keys?)([_. -]|$)', re.I)


def redact_document(text):
    from agent.redact import redact_sensitive_text
    return redact_sensitive_text(text, force=True, file_read=True,
                                 secret_file=True, redact_url_credentials=True)


def signature(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns,
            info.st_ctime_ns, info.st_nlink)


class VoiceDocuments:
    def __init__(self, catalog):
        # Never load configuration from a global/default profile or from tool args.
        if not isinstance(catalog, dict) or len(catalog) > 32:
            raise ValueError('document_catalog_invalid')
        self.catalog = dict(catalog)
        for name, path in self.catalog.items():
            if (not isinstance(name, str) or not ALIAS.fullmatch(name)
                    or not isinstance(path, str) or not path or len(path) > 4096):
                raise ValueError('document_catalog_invalid')

    def _read(self, raw_path):
        path = Path(raw_path)
        if (not path.is_absolute() or '..' in path.parts or path.name.startswith('.')
                or path.suffix.lower() not in {'.txt', '.md', '.csv'}
                or SENSITIVE.search(path.name)
                or any(p in {'.git', '.ssh', '.gnupg', 'secrets', 'credentials'} for p in path.parts)):
            raise ValueError('document_file_denied')
        # O_PATH permits traversal without granting directory enumeration. It also
        # works for Android ancestors that are searchable but not readable.
        directory = os.open(path.anchor, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW)
        fd = None
        try:
            for component in path.parts[1:-1]:
                child = os.open(component, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW,
                                dir_fd=directory)
                os.close(directory)
                directory = child
            fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                         dir_fd=directory)
            before = os.fstat(fd)
            if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                    or before.st_size > MAX_BYTES):
                raise ValueError('document_file_denied')
            data = bytearray()
            while len(data) <= MAX_BYTES:
                chunk = os.read(fd, min(8192, MAX_BYTES + 1 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
            after = os.fstat(fd)
            current = os.stat(path.name, dir_fd=directory, follow_symlinks=False)
            if len(data) > MAX_BYTES:
                raise ValueError('document_file_denied')
            if signature(before) != signature(after) or signature(after) != signature(current):
                raise ValueError('document_changed')
            return bytes(data)
        finally:
            if fd is not None:
                os.close(fd)
            os.close(directory)

    def perform(self, args):
        if not isinstance(args, dict) or not isinstance(args.get('action'), str):
            raise ValueError('invalid_document_arguments')
        action = args['action']
        if action == 'list_documents':
            if set(args) != {'action'}:
                raise ValueError('invalid_document_arguments')
            return {'documents': sorted(self.catalog), 'read_only': True,
                    'scope': 'operator_selected_text_documents'}
        if (action != 'read_document'
                or set(args) - {'action', 'name', 'offset', 'expected_sha256'}):
            raise ValueError('invalid_document_arguments')
        name = args.get('name')
        if not isinstance(name, str) or name not in self.catalog:
            raise ValueError('document_not_allowed')
        offset = args.get('offset', 0)
        expected = args.get('expected_sha256')
        if (type(offset) is not int or not 0 <= offset <= MAX_BYTES
                or ('expected_sha256' in args and
                    (not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected)))):
            raise ValueError('invalid_document_arguments')
        if offset and expected is None:
            raise ValueError('document_digest_required')
        data = self._read(self.catalog[name])
        digest = hashlib.sha256(data).hexdigest()
        if expected is not None and expected != digest:
            raise ValueError('document_changed')
        text = data.decode('utf-8-sig')
        if any(ord(c) < 32 and c not in '\n\r\t' for c in text):
            raise ValueError('document_file_denied')
        try:
            text = redact_document(text)
            if not isinstance(text, str):
                raise TypeError('redaction_result')
        except Exception:
            raise ValueError('document_redaction_unavailable') from None
        if offset > len(text):
            raise ValueError('invalid_document_arguments')
        end = min(offset + PAGE_CHARS, len(text))
        return {'name': name, 'content': text[offset:end], 'offset': offset,
                'next_offset': end, 'has_more': end < len(text), 'total_chars': len(text),
                'offset_unit': 'unicode_code_points', 'sha256': digest,
                'read_only': True, 'untrusted_file_data': True, 'redacted_view': True}
