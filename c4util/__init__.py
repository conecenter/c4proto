
import json
import pathlib
import subprocess
import time
import sys

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def one(it): return it

def parse_table(data):
    return [line.split() for line in data.split("\n") if len(line) > 0]

def read_json(path):
    with open(path,'r') as f:
        return json.load(f)

def path_exists(path):
    return pathlib.Path(path).exists()
def changing_text_observe(path, will): # we need to return save -- if code before save fails, state will remain unchanged
    return () if path_exists(path) and read_text(path) == will else (lambda:(
        pathlib.Path(path).write_text(will, encoding='utf-8', errors='strict')
    ),)
def changing_text(path, will):
    for save in changing_text_observe(path, will): save()
def read_text(path_str): return pathlib.Path(path_str).read_text(encoding='utf-8', errors='strict')


def decode(bs): return bs.decode(encoding='utf-8', errors='strict')


def log(text):
    print(text, file=sys.stderr)


def debug_args(hint, args):
    log(f"{hint}: {' '.join(str(a) for a in args)}")
    return args


def run(args, **opt):
    started = time.monotonic()
    res = subprocess.run(debug_args("running", args), check=True, **opt)
    log(f"{time.monotonic() - started}s for {args[0]}")
    return res


def Popen(args, **opt): return subprocess.Popen(debug_args("starting", args), **opt)


def wait_processes(processes):
    for proc in processes:
        proc.wait()
        log(f"finished with: {proc.returncode if proc.returncode == 0 else debug_args(proc.returncode, proc.args)}")
    return all(proc.returncode == 0 for proc in processes)


def run_text_out(args, **opt):
    return subprocess.run(debug_args("running", args), check=True, text=True, capture_output=True, **opt).stdout


def run_no_die(args, **opt): return subprocess.run(debug_args("running", args), **opt).returncode == 0


def never(a): raise Exception(a)


def never_if(e): return never(e) if e else e


def list_dir(d): return sorted(str(p) for p in pathlib.Path(d).iterdir())


def need_dir(d):
    pathlib.Path(d).mkdir(parents=True, exist_ok=True)
    return d

