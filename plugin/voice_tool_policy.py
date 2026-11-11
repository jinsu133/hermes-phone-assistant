"""Conservative voice access to existing file/calculation tools, not a shell sandbox.

Only new files below one per-request workspace and isolated literal arithmetic commands.
Unknown code, overwrites, connectors, credentials and arbitrary shell remain denied.
"""
import ast
import re
import shlex
from pathlib import Path


def arithmetic_command(args):
    if not isinstance(args, dict) or set(args)-{'command','timeout','background','workdir'}:
        return False
    if args.get('background') or args.get('workdir'):
        return False
    try:
        parts=shlex.split(args.get('command',''))
        if len(parts)!=5 or parts[:4] != ['python','-I','-S','-c']:
            return False
        tree=ast.parse(parts[4])
        if len(parts[4])>1000 or len(tree.body)!=1 or not isinstance(tree.body[0],ast.Expr):
            return False
        call=tree.body[0].value
        if not isinstance(call,ast.Call) or not isinstance(call.func,ast.Name) or call.func.id!='print' or len(call.args)!=1 or call.keywords:
            return False
        nodes=list(ast.walk(call.args[0]))
        allowed=(ast.Constant,ast.UnaryOp,ast.UAdd,ast.USub,ast.BinOp,ast.Add,ast.Sub,ast.Mult,ast.Div,ast.FloorDiv,ast.Mod)
        return len(nodes)<=100 and all(isinstance(n,allowed) and (not isinstance(n,ast.Constant) or (type(n.value) in (int,float) and abs(n.value)<1e15)) for n in nodes)
    except (ValueError,TypeError,SyntaxError,OverflowError):
        return False


def workspace(home, request_id):
    if not isinstance(request_id,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',request_id):
        raise ValueError('voice_request_invalid')
    return Path(home)/'voice-workspaces'/request_id


def file_allowed(tool, args, root):
    if tool not in {'read_file','write_file'} or not isinstance(args,dict): return False
    try:
        path=Path(args.get('path','')).expanduser()
        if not path.is_absolute() or '..' in path.parts: return False
        resolved=path.resolve()
        base=root.resolve()
        if base not in resolved.parents: return False
        if any(part.startswith('.') for part in path.relative_to(root).parts): return False
        if path.suffix.lower() not in {'.txt','.md','.csv','.json','.py','.html','.svg'}: return False
        if any(p.is_symlink() for p in (path,*path.parents)): return False
        if tool=='write_file':
            return not path.exists() and isinstance(args.get('content'),str) and len(args['content'])<=100000
        return path.is_file() and path.stat().st_size<=100000
    except (OSError,ValueError,TypeError):
        return False
