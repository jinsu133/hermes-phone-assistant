"""Initial unified pairing, immutable until runtime rotation is implemented.

One canonical file contains both independent role keys. Import never starts a
listener or claims verification. A changed existing bundle is refused, not merged.
"""
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import tempfile
from urllib.parse import urlsplit
import uuid

MAX_BYTES=16384


class PairingError(ValueError):
    pass


def canonical_path(home):
    return Path(home)/'state/phone-pairing.json'


def unique_object(items):
    result={}
    for key,value in items:
        if key in result: raise PairingError('PAIRING_DUPLICATE_FIELD')
        result[key]=value
    return result


def _uuid(value):
    try:
        return type(value) is str and str(uuid.UUID(value)) == value
    except (ValueError,AttributeError):
        return False


def validate(data):
    keys={'version','kind','pairing_id','revision','device_instance_id','ui','voice'}
    if type(data) is not dict or set(data)!=keys:
        raise PairingError('PAIRING_SCHEMA_INVALID')
    if type(data['version']) is not int or data['version']!=2 or data['kind']!='hermes-phone':
        raise PairingError('PAIRING_SCHEMA_INVALID')
    if type(data['revision']) is not int or not 1<=data['revision']<2**63:
        raise PairingError('PAIRING_SCHEMA_INVALID')
    if not _uuid(data['pairing_id']) or not _uuid(data['device_instance_id']):
        raise PairingError('PAIRING_ID_INVALID')
    for role in ('ui','voice'):
        part=data[role]
        if type(part) is not dict or set(part)!={'url','token','protocol_version'}:
            raise PairingError('PAIRING_SCHEMA_INVALID')
        if type(part['protocol_version']) is not int or part['protocol_version']!=1:
            raise PairingError('PAIRING_PROTOCOL_UNSUPPORTED')
        if type(part['url']) is not str or type(part['token']) is not str:
            raise PairingError('PAIRING_SCHEMA_INVALID')
        try:
            url=urlsplit(part['url'])
            port=url.port
            exact=f'http://127.0.0.1:{port}/'+('mcp' if role=='ui' else '')
            if not port or part['url']!=exact: raise ValueError()
            # The shipped APK voice client still has a fixed port. Do not accept
            # a configurable address until every actual consumer supports it.
            if role=='voice' and port!=5001: raise ValueError()
        except ValueError:
            raise PairingError('PAIRING_ADDRESS_UNSUPPORTED') from None
        pattern=r'[A-Za-z0-9_-]{43}=' if role=='ui' else r'[A-Za-z0-9_-]{32,512}'
        if not re.fullmatch(pattern,part['token']):
            raise PairingError('PAIRING_CREDENTIAL_INVALID')
    if data['ui']['url'].split(':')[-1].split('/')[0]=='5001':
        raise PairingError('PAIRING_PORT_CONFLICT')
    return data


def read_bundle(path, *, private=True):
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or not 0<info.st_size<=MAX_BYTES:
                raise PairingError('PAIRING_FILE_INVALID')
            if private and (info.st_uid!=os.getuid() or info.st_mode & 0o077):
                raise PairingError('PAIRING_FILE_NOT_PRIVATE')
            raw=stream.read(MAX_BYTES+1)
        if len(raw)>MAX_BYTES: raise PairingError('PAIRING_FILE_INVALID')
        return validate(json.loads(raw,object_pairs_hook=unique_object))
    except PairingError:
        raise
    except (OSError,ValueError,TypeError,UnicodeError):
        raise PairingError('PAIRING_FILE_INVALID') from None


def public_status(data):
    return dict(pairing_id=data['pairing_id'],revision=data['revision'],
                device_instance_id=data['device_instance_id'],state='Imported',
                runtime_applied=False,verified=False)


def store_initial(source,home):
    value=read_bundle(source,private=False)
    target=canonical_path(home)
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.parent.is_symlink(): raise PairingError('PAIRING_DIRECTORY_INVALID')
    lockfd=os.open(target.parent/'phone-pairing.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        fcntl.flock(lockfd,fcntl.LOCK_EX)
        if target.exists() or target.is_symlink():
            if read_bundle(target)!=value:
                raise PairingError('PAIRING_ROTATION_NOT_SUPPORTED')
            return public_status(value)
        fd,name=tempfile.mkstemp(prefix='.phone-pairing-',dir=target.parent)
        try:
            with os.fdopen(fd,'wb') as out:
                os.fchmod(out.fileno(),0o600)
                out.write(json.dumps(value,separators=(',',':')).encode())
                out.flush();os.fsync(out.fileno())
            os.replace(name,target)
            directory=os.open(target.parent,os.O_RDONLY|os.O_DIRECTORY)
            try: os.fsync(directory)
            finally: os.close(directory)
        finally:
            if os.path.exists(name): os.unlink(name)
    finally:
        os.close(lockfd)
    return public_status(value)


def ui_connection(data):
    data=validate(data)
    return dict(version=1,url=data['ui']['url'],token=data['ui']['token'],
                instance_id=data['device_instance_id'])
