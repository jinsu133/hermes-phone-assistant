"""Trusted child bootstrap; never import Hermes, site, profiles or credentials."""
import ctypes
import os
import platform
import resource
import sys
# Preload only standard-library compute modules before closing filesystem access.
import collections, csv, datetime, decimal, fractions, functools, hashlib, io
import itertools, json, math, random, re, statistics, string, time
import socket  # Loaded only to test/document that kernel socket() remains denied.


def isolate():
    if sys.platform not in ('linux','android') or platform.machine()!='aarch64':
        raise RuntimeError('unsupported_isolation_platform')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    resource.setrlimit(resource.RLIMIT_FSIZE,(0,0))
    resource.setrlimit(resource.RLIMIT_CPU,(2,3))
    # Android Scudo reserves gigabytes of virtual address space before user code.
    with open("/proc/self/status",encoding="ascii") as f:
        vmsize=next(int(line.split()[1])*1024 for line in f if line.startswith("VmSize:"))
    resource.setrlimit(resource.RLIMIT_DATA,(96*1024*1024,96*1024*1024))
    resource.setrlimit(resource.RLIMIT_AS,(vmsize+128*1024*1024,vmsize+128*1024*1024))
    class Filter(ctypes.Structure):
        _fields_=[('code',ctypes.c_ushort),('jt',ctypes.c_ubyte),('jf',ctypes.c_ubyte),('k',ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_=[('len',ctypes.c_ushort),('filter',ctypes.POINTER(Filter))]
    # Audit architecture check precedes syscall matching. Native aarch64 numbers.
    raw=[(0x20,0,0,4),(0x15,1,0,0xc00000b7),(0x06,0,0,0x80000000),(0x20,0,0,0)]
    # read/write are safe only because exactly the three parent-created stdio pipes
    # survive and no descriptor-creating syscall is allowed after this point.
    allowed=(63,64,80,93,94,98,113,115,124,132,134,135,139,165,172,178,214,215,216,222,226,233,278)
    for nr in allowed:raw.extend([(0x15,0,1,nr),(0x06,0,0,0x7fff0000)])
    raw.append((0x06,0,0,0x00050001))
    filters=(Filter*len(raw))(*(Filter(*i) for i in raw));program=Program(len(raw),filters)
    libc=ctypes.CDLL(None,use_errno=True)
    for fd in os.listdir("/proc/self/fd"):
        if int(fd)>2:
            try:os.close(int(fd))
            except OSError:pass
    if libc.prctl(38,1,0,0,0)!=0:raise RuntimeError('no_new_privs_failed')
    if libc.prctl(22,2,ctypes.byref(program),0,0)!=0:raise RuntimeError('seccomp_failed')


def main():
    source=sys.stdin.buffer.read(8193)
    if len(source)>8192:raise ValueError('source_limit')
    source=source.decode('utf-8')
    isolate()  # No user compilation or execution before the kernel filter.
    os.write(1,b'HERMES_COMPUTE_READY\n')
    exit_code=0
    try:
        exec(compile(source,'<voice-program>','exec'),{'__name__':'__main__'})
    except BaseException as exc:
        exit_code=1
        print(type(exc).__name__+': '+str(exc)[:1000],file=sys.stderr)
    finally:
        sys.stdout.flush();sys.stderr.flush()
    os._exit(exit_code)

if __name__=='__main__':main()
