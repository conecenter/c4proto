
import json
from pathlib import Path
import subprocess
import time
import sys
from types import MappingProxyType

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def one(it): return it

def read_json(path): return json.loads(Path(path).read_bytes())

def path_exists(path): return Path(path).exists()
def changing_text_observe(path, will): # we need to return save -- if code before save fails, state will remain unchanged
    return () if path_exists(path) and read_text(path) == will else (lambda: Path(path).write_bytes(will.encode()),)
def changing_text(path, will):
    for save in changing_text_observe(path, will): save()
def read_text(path_str): return Path(path_str).read_bytes().decode()

def log(text): print(text, file=sys.stderr)

def debug_args(hint, args):
    log(f"{hint}: {' '.join(str(a) for a in args)}")
    return args

def run(args, **opt):
    started = time.monotonic()
    res = subprocess.run(debug_args("running", args), check=True, **opt)
    log(f"{time.monotonic() - started}s for {args[0]}")
    return res

def Popen(args, **opt): return subprocess.Popen(debug_args("starting", args), **opt)

def run_text_out(args, **opt):
    return subprocess.run(debug_args("running", args), check=True, text=True, capture_output=True, **opt).stdout

def run_no_die(args, **opt): return subprocess.run(debug_args("running", args), **opt).returncode == 0

def never(a): raise Exception(a)

def list_dir(d): return sorted(str(p) for p in Path(d).iterdir())

def never_if(e): return never(e) if e else e

def http_exchange(conn, method, url, data=b'', headers=MappingProxyType({})):
    conn.request(method, url, data, headers)
    resp = conn.getresponse()
    msg = resp.read()
    return resp.status, msg

def http_check(status, msg): return msg if 200 <= status < 300 else never(f"request failed: {status}\n{msg}")

def repeat(f, exits=()):
    while f() not in exits: pass

def decode(bs): return bs.decode()
