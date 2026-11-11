"""Frozen, voice-confirmed textual revision; preserve the original document.

Produces a new private current-request draft, never overwrites the source. A
changed source invalidates the proposal; the model cannot submit an approval.
"""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import secrets
import unicodedata


def digest(data):
    return hashlib.sha256(data).hexdigest()


@dataclass(frozen=True)
class Revision:
    request: str
    name: str
    before: str
    after: str
    old: str
    new: str
    content: str
    output_name: str
    action_digest: str

    def native_args(self):
        return dict(voice_request_id=self.request, name=self.name, before=self.before,
                    after=self.after, old_text=self.old, new_text=self.new,
                    action_digest=self.action_digest)


def prepare_revision(documents, request, args):
    fields = {'action','name','expected_sha256','old_text','new_text'}
    if not isinstance(args,dict) or set(args)!=fields or args['action']!='revise_document':
        raise ValueError('invalid_revision_arguments')
    name=args['name']; old=args['old_text']; new=args['new_text']; expected=args['expected_sha256']
    if not isinstance(request,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',request):
        raise ValueError('revision_owner_required')
    if not isinstance(name,str) or name not in documents.catalog:
        raise ValueError('document_not_allowed')
    if not isinstance(expected,str) or not re.fullmatch('[a-f0-9]{64}',expected):
        raise ValueError('document_digest_required')
    if (not isinstance(old,str) or not 1<=len(old)<=160 or not isinstance(new,str) or len(new)>160
            or old==new or any(unicodedata.category(c).startswith('C') and c not in '\n\r\t' for c in old+new)):
        raise ValueError('invalid_revision_arguments')
    source=documents._read(documents.catalog[name])
    if digest(source)!=expected: raise ValueError('document_changed')
    text=source.decode('utf-8-sig')
    if text.count(old)!=1: raise ValueError('revision_match_not_unique')
    edited=text.replace(old,new,1)
    if len(edited.encode('utf-8'))>65536: raise ValueError('revision_too_large')
    from agent.redact import redact_sensitive_text
    for value in (text,edited):
        if any(unicodedata.category(c).startswith('C') and c not in '\n\r\t' for c in value):
            raise ValueError('revision_sensitive_content')
        if redact_sensitive_text(value,force=True,file_read=True,secret_file=True,redact_url_credentials=True)!=value:
            raise ValueError('revision_sensitive_content')
    after=digest(edited.encode('utf-8'))
    action_digest=digest('\0'.join((request,name,expected,after,old,new)).encode('utf-8'))
    suffix=Path(documents.catalog[name]).suffix.lower()
    output='revision-'+secrets.token_hex(8)+suffix
    return Revision(request,name,expected,after,old,new,edited,output,action_digest)


def save_revision(documents, drafts, stage, receipt):
    if (not isinstance(receipt,dict) or receipt.get('state')!='completed'
            or receipt.get('permission_only') is not True or receipt.get('dispatched') is not True
            or receipt.get('voice_request_id')!=stage.request
            or receipt.get('authorized_digest')!=stage.action_digest):
        raise ValueError('revision_not_authorized')
    if digest(documents._read(documents.catalog[stage.name]))!=stage.before:
        raise ValueError('document_changed')
    result=drafts.perform(stage.request,dict(action='write',name=stage.output_name,content=stage.content))
    verified=drafts.perform(stage.request,dict(action='read',name=stage.output_name))
    if verified['sha256']!=stage.after: raise ValueError('revision_verification_failed')
    return dict(result,source_name=stage.name,source_sha256=stage.before,
                original_preserved=True,revision_copy=True,verified=True)
