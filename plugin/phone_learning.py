"""Bounded procedural UI learning; recipes are data, never approval capabilities.

Only linear, pinned, single-app routes are generalized. An anchor is a native resource
id, a structural role, or a short control label (a button word such as "도착"). Labels let
routes be learned in any app, including screens drawn in a WebView that have no ids, but
only a bounded control label is kept: never page content, the user's query, numeric
text, addresses, links or risk wording. Persisted plans contain no task text, literal
inputs, credentials or approval receipts. A label-anchored route is reused only after it
was verified LABEL_CONFIRMATIONS times. A final fresh match verifies screen arrival, NOT
semantic user-goal success.
"""
from contextlib import contextmanager
from copy import deepcopy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import uuid

MAX_BYTES=512*1024
MAX_ROUTES=128
ROLES={'search_field','search_suggestion','conversation_row'}
OPS={'launch','await','focus','input','submit','select','click','assert'}
LABEL_OPS={'await','select','click','assert'}
LABEL_MAX=16
LABEL_CONFIRMATIONS=2
# Final effects and account/security wording are never learned as anchors.
_LABEL_RISK=re.compile(r'결제|송금|이체|구매|주문|예약|삭제|전송|보내|전화|통화|구독|동의|권한|비밀번호|인증|로그인|'
    r'pay|buy|purchase|checkout|subscribe|send|call|dial|delete|login|password|agree|allow',re.I)


def _control_label(value,queries):
    """True for a short button word, never content the screen or the user supplied."""
    if not isinstance(value,str) or value!=value.strip() or not 1<=len(value)<=LABEL_MAX:return False
    if len(value.split())>3 or any(c.isdigit() for c in value):return False
    if any(c in value for c in '@/:'+chr(92)+'<>{}') or any(ord(c)<32 for c in value):return False
    if _LABEL_RISK.search(value):return False
    # A label that carries the spoken query is the user's content, not the app's control.
    return not any(isinstance(q,str) and q.strip() and q.strip() in value for q in queries)

class LearningError(ValueError):
    pass


def template(plan):
    from .phone_profiles import _check_steps, ProfileError
    if type(plan) is not dict or set(plan)-{'package','version_code','budget_ms','steps','task'}:
        return None
    pkg=plan.get('package'); version=plan.get('version_code'); steps=plan.get('steps')
    if not isinstance(pkg,str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+',pkg):return None
    if len(pkg)>160 or type(version) is not int or not 0<=version<2**63:return None
    if type(steps) is not list or not 2<=len(steps)<=24:return None
    try:_check_steps(steps,'learning')
    except ProfileError:return None
    if steps[0]!={'op':'launch','package':pkg} or steps[-1].get('op')!='assert':return None
    budget=plan.get('budget_ms',8000)
    if type(budget) is not int or not 500<=budget<=15000:return None
    output=[];inputs=0
    queries=[s.get('value') for s in steps if s.get('op')=='input']
    for index,step in enumerate(steps):
        op=step['op']
        if op not in OPS or (op=='launch' and index!=0):return None
        if any(k in step for k in ('text_contains','index')):return None
        if 'text' in step and (op not in LABEL_OPS or not _control_label(step['text'],queries)):return None
        if op!='launch' and not set(step)&{'id','id_suffix','role','text'}:return None
        for key in ('id','id_suffix'):
            value=step.get(key)
            if value is not None:
                # Native resource identifiers only; never arbitrary screen text.
                pattern=(re.escape(pkg)+r':id/[A-Za-z_][A-Za-z0-9_]{0,95}') if key=='id' else r'[A-Za-z_][A-Za-z0-9_]{0,95}'
                if not isinstance(value,str) or not re.fullmatch(pattern,value):return None
        if 'role' in step and step['role'] not in ROLES:return None
        clean=deepcopy(step)
        if op=='input':
            if step.get('role')!='search_field' or not isinstance(step.get('value'),str):return None
            inputs+=1
            if inputs>1:return None
            clean['value']='{q}'
        output.append(clean)
    return dict(package=pkg,version_code=version,budget_ms=budget,steps=output)


def verified(plan,result,screen):
    n=len(plan['steps'])
    if type(result) is not dict or result.get('ok') is not True:return False
    if type(result.get('completed')) is not int or type(result.get('total')) is not int:return False
    if result['completed']!=n or result['total']!=n:return False
    if type(screen) is not dict or screen.get('package')!=plan['package'] or screen.get('truncated') is not False:return False
    if not isinstance(screen.get('snapshot'),str) or not screen['snapshot']:return False
    nodes=screen.get('nodes')
    if type(nodes) is not list or len(nodes)>100:return False
    selector={k:v for k,v in plan['steps'][-1].items() if k!='op'}
    def matches(node):
        if type(node) is not dict:return False
        for key,value in selector.items():
            if key=='id' and node.get('resource_id')!=value:return False
            if key=='id_suffix' and not str(node.get('resource_id','')).endswith('/'+value):return False
            if key=='role' and node.get('role')!=value:return False
            if key=='text' and value not in (node.get('text'),node.get('label')):return False
        return True
    return sum(matches(node) for node in nodes)==1


def _id(device,plan):
    return hashlib.sha256(json.dumps([device,plan],sort_keys=True,separators=(',',':')).encode()).hexdigest()


def _path(home):return Path(home)/'state/phone-learning.json'


def _valid_device(value):
    try:return isinstance(value,str) and str(uuid.UUID(value))==value
    except (ValueError,AttributeError):return False


def _read(home):
    path=_path(home)
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except FileNotFoundError:return {'schema':1,'entries':{}}
    except OSError:raise LearningError('LEARNING_STORE_INVALID') from None
    try:
        with os.fdopen(fd,'rb') as file:
            info=os.fstat(file.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode & 0o077 or info.st_size>MAX_BYTES:raise ValueError()
            raw=file.read(MAX_BYTES+1)
        if len(raw)>MAX_BYTES:raise ValueError()
        from .pairing import unique_object
        data=json.loads(raw,object_pairs_hook=unique_object)
        if type(data) is not dict or set(data)!={'schema','entries'} or type(data['schema']) is not int or data['schema']!=1:raise ValueError()
        if type(data['entries']) is not dict or len(data['entries'])>MAX_ROUTES:raise ValueError()
        for identity,entry in data['entries'].items():
            if type(entry) is not dict or set(entry)!={'device','plan','successes','failures','streak','total_ms','paused'}:raise ValueError()
            if not _valid_device(entry['device']):raise ValueError()
            parsed=template(entry['plan'])
            if parsed!=entry['plan'] or parsed is None or identity!=_id(entry['device'],parsed):raise ValueError()
            for field in ('successes','failures','streak','total_ms'):
                if type(entry[field]) is not int or not 0<=entry[field]<2**63:raise ValueError()
            if type(entry['paused']) is not bool or entry['successes']<1 or entry['streak']>entry['successes']:raise ValueError()
        return data
    except (OSError,ValueError,TypeError,KeyError,UnicodeError):
        raise LearningError('LEARNING_STORE_INVALID') from None


@contextmanager
def _locked(home):
    path=_path(home);path.parent.mkdir(parents=True,exist_ok=True)
    if path.parent.is_symlink():raise LearningError('LEARNING_STORE_INVALID')
    fd=os.open(path.parent/'.phone-learning.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise LearningError('LEARNING_BUSY') from None
        yield
    finally:os.close(fd)


def _write(home,data):
    payload=json.dumps(data,sort_keys=True,separators=(',',':')).encode()
    if len(payload)>MAX_BYTES:raise LearningError('LEARNING_STORE_FULL')
    path=_path(home);fd,name=tempfile.mkstemp(prefix='.learning-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as file:
            os.fchmod(file.fileno(),0o600);file.write(payload);file.flush();os.fsync(file.fileno())
        os.replace(name,path)
        parent=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(parent)
        finally:os.close(parent)
    finally:
        if os.path.exists(name):os.unlink(name)


def record(home,device,plan,success,elapsed_ms):
    parsed=template(plan)
    if parsed is None or not _valid_device(device) or type(success) is not bool:raise LearningError('LEARNING_INVALID')
    if type(elapsed_ms) is not int or not 0<=elapsed_ms<=120000:raise LearningError('LEARNING_INVALID')
    identity=_id(device,parsed)
    with _locked(home):
        data=_read(home);entry=data['entries'].get(identity)
        if entry is None:
            if not success:return {'state':'unverified'}
            if len(data['entries'])>=MAX_ROUTES:return {'state':'store_full'}
            entry=dict(device=device,plan=parsed,successes=0,failures=0,streak=0,total_ms=0,paused=False)
        if success:
            entry['successes']+=1;entry['streak']+=1;entry['total_ms']+=elapsed_ms;entry['paused']=False
        else:
            entry['failures']+=1;entry['streak']=0;entry['paused']=True
        data['entries'][identity]=entry;_write(home,data)
    return {'state':'verified_screen' if success else 'paused','route_id':identity,
            'next_step':'reuse_available' if success else 'reobserve_and_replan_no_automatic_retry'}


def _label_anchored(plan):
    return any('text' in step for step in plan['steps'])


def list_routes(home, device=None):
    entries=_read(home)['entries']
    rows=[]
    for identity,entry in entries.items():
        if device is not None and entry['device']!=device:continue
        plan=entry['plan']
        rows.append(dict(route_id=identity,package=plan['package'],version_code=plan['version_code'],
            target={k:v for k,v in plan['steps'][-1].items() if k!='op'},
            operations=[s['op'] for s in plan['steps']],needs_query=any(s['op']=='input' for s in plan['steps']),
            successes=entry['successes'],failures=entry['failures'],
            anchors='label' if _label_anchored(plan) else 'structural',
            reusable=not entry['paused'] and (not _label_anchored(plan) or entry['successes']>=LABEL_CONFIRMATIONS),
            status='paused' if entry['paused'] else ('repeatedly_verified' if entry['streak']>=3 else 'candidate'),
            mean_handler_ms=entry['total_ms']//entry['successes']))
    # Recommend only among repeated screen-verified alternatives with the same
    # app/version/target/input shape. This is a planning hint, never permission.
    fastest={}
    for row in rows:
        group=(row['package'],row['version_code'],json.dumps(row['target'],sort_keys=True),row['needs_query'])
        if row['status']=='repeatedly_verified':
            rank=(row['mean_handler_ms'],len(row['operations']),row['route_id'])
            if group not in fastest or rank<fastest[group]:fastest[group]=rank
    for row in rows:
        group=(row['package'],row['version_code'],json.dumps(row['target'],sort_keys=True),row['needs_query'])
        row['recommended']=row['status']=='repeatedly_verified' and fastest.get(group,())[2:]==(row['route_id'],)
    return sorted(rows,key=lambda r:(r['status']=='paused',not r['recommended'],r['mean_handler_ms'],-r['successes'],r['route_id']))


def resolve(home,identity,device,query=None):
    if not isinstance(identity,str) or not re.fullmatch('[0-9a-f]{64}',identity):raise LearningError('LEARNING_INVALID')
    entry=_read(home)['entries'].get(identity)
    if entry is None:raise LearningError('LEARNING_NOT_FOUND')
    if entry['device']!=device:raise LearningError('LEARNING_DEVICE_CHANGED')
    if entry['paused']:raise LearningError('LEARNING_REPLAN_REQUIRED')
    if _label_anchored(entry['plan']) and entry['successes']<LABEL_CONFIRMATIONS:
        raise LearningError('LEARNING_UNCONFIRMED')
    plan=deepcopy(entry['plan'])
    for step in plan['steps']:
        if step['op']=='input':
            if not isinstance(query,str) or not query.strip() or len(query)>200:raise LearningError('LEARNING_QUERY_REQUIRED')
            step['value']=query
    return plan


def forget(home,identity):
    if not isinstance(identity,str) or not re.fullmatch('[0-9a-f]{64}',identity):raise LearningError('LEARNING_INVALID')
    with _locked(home):
        data=_read(home);removed=data['entries'].pop(identity,None) is not None
        _write(home,data)
    return removed
