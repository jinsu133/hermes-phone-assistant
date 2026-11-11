"""Private current-request drafts. No shell, imports of draft code, or network.

Revision requires both in-memory provenance and an exact previous digest. Uses
openat/O_NOFOLLOW and descriptor verification; cannot adopt existing/user files.
Syntax checking parses text only and is never represented as running tests.
"""
import ast
import math
import operator
from collections import OrderedDict
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import threading

LIMIT=65536

def digest(data):
    return hashlib.sha256(data).hexdigest()

class DraftWorkspace:
    def __init__(self, home):
        self.home=Path(home)
        self.owned=OrderedDict()
        self.lock=threading.RLock()

    def _directory(self, request):
        fd=os.open(self.home,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            for component in ('voice-workspaces',request):
                try: os.mkdir(component,0o700,dir_fd=fd)
                except FileExistsError: pass
                next_fd=os.open(component,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
                os.close(fd);fd=next_fd
                info=os.fstat(fd)
                if info.st_uid!=os.getuid() or info.st_mode & 0o077:
                    raise ValueError('private_directory_required')
            return fd
        except BaseException:
            os.close(fd);raise

    def _read(self,fd):
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_size>LIMIT:
            raise ValueError('draft_file_denied')
        os.lseek(fd,0,os.SEEK_SET)
        data=b''
        while len(data)<=LIMIT:
            chunk=os.read(fd,min(8192,LIMIT+1-len(data)))
            if not chunk: break
            data+=chunk
        if len(data)>LIMIT: raise ValueError('draft_too_large')
        return data

    def perform(self,request,args):
        if not isinstance(request,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',request):
            raise ValueError('voice_owner_required')
        fields={'list':{'action'},'write':{'action','name','content','expected_sha256'},'read':{'action','name'},'check':{'action','name'},'run_numeric':{'action','name'}}
        if not isinstance(args,dict) or args.get('action') not in fields or not set(args)<=fields[args['action']]:
            raise ValueError('invalid_draft_arguments')
        action=args['action'];name=args.get('name','')
        if action!='list' and (not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9가-힣][A-Za-z0-9가-힣 _-]{0,70}\.(txt|md|csv|json|py|html|svg)',name)):
            raise ValueError('invalid_draft_name')
        content=args.get('content')
        if action=='write' and (not isinstance(content,str) or len(content.encode('utf-8'))>LIMIT):
            raise ValueError('draft_too_large')
        with self.lock:
            owned=self.owned.setdefault(request,{})
            self.owned.move_to_end(request)
            while len(self.owned)>64: self.owned.popitem(last=False)
            if action=='list': return {'files':sorted(owned),'scope':'current_request_only'}
            if name not in owned and action!='write': raise ValueError('draft_not_owned')
            if len(owned)>=32 and name not in owned: raise ValueError('draft_count_limit')
            directory=self._directory(request)
            fd=None
            try:
                flags=os.O_NOFOLLOW|os.O_NONBLOCK|(os.O_RDWR if action=='write' else os.O_RDONLY)
                if name not in owned:
                    if args.get('expected_sha256'): raise ValueError('draft_not_owned')
                    flags|=os.O_CREAT|os.O_EXCL
                fd=os.open(name,flags,0o600,dir_fd=directory)
                data=self._read(fd)
                if name in owned and digest(data)!=owned[name]: raise ValueError('draft_changed')
                if action=='write':
                    if name in owned and args.get('expected_sha256')!=owned[name]: raise ValueError('draft_digest_required')
                    data=content.encode('utf-8')
                    os.lseek(fd,0,os.SEEK_SET)
                    view=memoryview(data)
                    while view:
                        written=os.write(fd,view)
                        if written<=0: raise OSError('short_write')
                        view=view[written:]
                    os.ftruncate(fd,len(data));os.fsync(fd)
                    if self._read(fd)!=data: raise OSError('draft_verification_failed')
                    owned[name]=digest(data)
                    return {'path':str(self.home/'voice-workspaces'/request/name),'sha256':owned[name],'verified':True,'bytes':len(data)}
                text=data.decode('utf-8')
                if action=='read': return {'content':text,'sha256':digest(data),'untrusted_file_data':True}
                suffix=Path(name).suffix
                if action=='run_numeric':
                    if suffix!='.py': raise ValueError('numeric_python_only')
                    return NumericProgram().run(text)
                if suffix not in {'.py','.json'}: return {'supported':False,'executed':False,'reason':'syntax_check_supports_python_and_json_only'}
                try:
                    if suffix=='.py': ast.parse(text,filename=name)
                    else: json.loads(text)
                    return {'valid':True,'check':'syntax_only','executed':False,'sha256':digest(data)}
                except (SyntaxError,ValueError,RecursionError) as exc:
                    return {'valid':False,'check':'syntax_only','executed':False,'line':getattr(exc,'lineno',None)}
            finally:
                if fd is not None: os.close(fd)
                os.close(directory)


class NumericProgram:
    """Interpret numeric AST, not Python eval/exec. No objects, strings, I/O or imports.

    Deliberately not a general Python sandbox. Supports bounded assignments, range
    loops, arithmetic, comparisons, assertions and numeric print for small programs.
    """
    OPS={ast.Add:operator.add,ast.Sub:operator.sub,ast.Mult:operator.mul,
         ast.Div:operator.truediv,ast.FloorDiv:operator.floordiv,ast.Mod:operator.mod}
    COMP={ast.Eq:operator.eq,ast.NotEq:operator.ne,ast.Lt:operator.lt,
          ast.LtE:operator.le,ast.Gt:operator.gt,ast.GtE:operator.ge}
    def __init__(self):
        self.values={};self.steps=0;self.output=[];self.assertions=0
    def tick(self):
        self.steps+=1
        if self.steps>5000: raise ValueError('numeric_step_limit')
    def number(self,value):
        if type(value) not in (int,float,bool) or not math.isfinite(value) or abs(value)>1e15:
            raise ValueError('numeric_value_limit')
        return value
    def name(self,node):
        if not isinstance(node,ast.Name) or node.id.startswith('_') or len(node.id)>40:
            raise ValueError('numeric_name_denied')
        return node.id
    def value(self,node):
        self.tick()
        if isinstance(node,ast.Constant): return self.number(node.value)
        if isinstance(node,ast.Name): return self.number(self.values[self.name(node)])
        if isinstance(node,ast.UnaryOp) and isinstance(node.op,(ast.UAdd,ast.USub)):
            return self.number(self.value(node.operand)*(1 if isinstance(node.op,ast.UAdd) else -1))
        if isinstance(node,ast.BinOp) and type(node.op) in self.OPS:
            return self.number(self.OPS[type(node.op)](self.value(node.left),self.value(node.right)))
        if isinstance(node,ast.Compare):
            left=self.value(node.left)
            for op,right_node in zip(node.ops,node.comparators):
                if type(op) not in self.COMP: raise ValueError('numeric_comparison_denied')
                right=self.value(right_node)
                if not self.COMP[type(op)](left,right): return False
                left=right
            return True
        raise ValueError('numeric_expression_denied')
    def statements(self,nodes):
        for n in nodes:
            self.tick()
            if isinstance(n,ast.Assign) and len(n.targets)==1:
                key=self.name(n.targets[0]);self.values[key]=self.value(n.value)
                if len(self.values)>64: raise ValueError('numeric_variable_limit')
            elif isinstance(n,ast.Assert) and n.msg is None:
                self.assertions+=1
                if not self.value(n.test): raise ValueError('numeric_assertion_failed')
            elif (isinstance(n,ast.Expr) and isinstance(n.value,ast.Call)
                  and isinstance(n.value.func,ast.Name) and n.value.func.id=='print'
                  and not n.value.keywords and 1<=len(n.value.args)<=8):
                self.output.append(' '.join(str(self.value(v)) for v in n.value.args))
                if sum(map(len,self.output))>4000: raise ValueError('numeric_output_limit')
            elif (isinstance(n,ast.For) and not n.orelse and isinstance(n.iter,ast.Call)
                  and isinstance(n.iter.func,ast.Name) and n.iter.func.id=='range'
                  and not n.iter.keywords and 1<=len(n.iter.args)<=3):
                key=self.name(n.target);args=[self.value(v) for v in n.iter.args]
                if any(type(v)!=int or abs(v)>10000 for v in args): raise ValueError('numeric_range_limit')
                values=range(*args)
                if len(values)>1000: raise ValueError('numeric_range_limit')
                for value in values:
                    self.values[key]=value;self.statements(n.body)
            else: raise ValueError('numeric_statement_denied')
    def run(self,text):
        result={'runtime':'restricted_numeric_ast','executed':False,'success':False}
        try:
            if len(text)>8000: raise ValueError('numeric_source_limit')
            tree=ast.parse(text)
            if sum(1 for _ in ast.walk(tree))>250: raise ValueError('numeric_ast_limit')
            result['executed']=True
            self.statements(tree.body)
            result.update(success=True,stdout='\n'.join(self.output),assertions_passed=self.assertions)
        except (SyntaxError,ValueError,KeyError,ZeroDivisionError,OverflowError,RecursionError):
            result['error']='numeric_program_rejected_or_failed'
        result['steps']=self.steps
        return result

