"""Fresh-process, Linux/aarch64-only compute runner. No shell or network.

All environment and extra descriptors are removed; seccomp denies every syscall
except a small compute/stdio set. Fail closed if installation fails. This is not
protection against kernel vulnerabilities and is not a general host terminal.
"""
import os
from pathlib import Path
import selectors
import subprocess
import sys
import threading
import time

READY=b'HERMES_COMPUTE_READY\n'
LIMIT=32768


def run_python(source, cancel=None):
    if not isinstance(source,str) or len(source.encode('utf-8'))>8192:
        raise ValueError('python_source_limit')
    cancel=cancel or threading.Event()
    if cancel.is_set(): return dict(executed=False,stop_reason='cancelled',stdout='',stderr='',exit_code=None)
    command=[sys.executable,'-I','-S',str(Path(__file__).with_name('voice_python_child.py'))]
    env={'LANG':'C.UTF-8','PATH':'/data/data/com.termux/files/usr/bin','PYTHONIOENCODING':'utf-8'}
    child=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                           env=env,cwd='/',close_fds=True,start_new_session=True)
    selector=selectors.DefaultSelector();buffers={'stdout':bytearray(),'stderr':bytearray()}
    reason='completed';deadline=time.monotonic()+5.0
    try:
        payload=source.encode('utf-8');sent=0
        if payload:
            os.set_blocking(child.stdin.fileno(),False)
            selector.register(child.stdin,selectors.EVENT_WRITE,'stdin')
        else:child.stdin.close()
        for name in buffers:
            stream=getattr(child,name);os.set_blocking(stream.fileno(),False);selector.register(stream,selectors.EVENT_READ,name)
        while selector.get_map():
            if cancel.is_set(): reason='cancelled';break
            if time.monotonic()>=deadline: reason='timeout';break
            for key,_ in selector.select(0.05):
                if key.data=='stdin':
                    try:sent+=os.write(child.stdin.fileno(),payload[sent:sent+4096])
                    except BrokenPipeError:sent=len(payload)
                    if sent>=len(payload):selector.unregister(child.stdin);child.stdin.close()
                    continue
                data=os.read(key.fileobj.fileno(),4096)
                if not data:selector.unregister(key.fileobj);continue
                buf=buffers[key.data];available=LIMIT+len(READY)-len(buf)
                buf.extend(data[:max(0,available)])
                if len(data)>available:reason='output_limit';break
            if reason!='completed':break
        if child.poll() is None:
            if reason!='completed':child.kill()
            else:
                try:child.wait(timeout=max(.01,deadline-time.monotonic()))
                except subprocess.TimeoutExpired:reason='timeout';child.kill()
        child.wait(timeout=2)
    finally:
        if child.poll() is None:child.kill();child.wait(timeout=2)
        selector.close()
        for stream in (child.stdin,child.stdout,child.stderr):stream.close()
    output=bytes(buffers['stdout']);ready=output.startswith(READY)
    output=(output[len(READY):] if ready else output)[:LIMIT]
    return dict(executed=ready,exit_code=child.returncode,stop_reason=reason if ready or reason!='completed' else 'isolation_unavailable',
                stdout=output.decode('utf-8','replace'),stderr=bytes(buffers['stderr'])[:LIMIT].decode('utf-8','replace'),
                isolation='linux_aarch64_seccomp_no_file_network_process',untrusted_program_output=True)
