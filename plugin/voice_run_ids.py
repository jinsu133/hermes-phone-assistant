"""Shared exact public session-ID contract; never normalize an identifier."""
import re

_SESSION = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.:\-]{0,255}\Z', re.ASCII)


def valid_session_id(value):
    return (type(value) is str and _SESSION.fullmatch(value) is not None
            and not value.startswith(('sk-', 'AIza', 'Bearer', 'ghp_', 'github_pat_')))
