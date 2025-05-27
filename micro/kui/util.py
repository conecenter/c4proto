
import json
import subprocess
from pathlib import Path
from sys import stderr

def one(it): return it
def never(m): raise Exception(m)
def write_text(path, text): Path(path).write_text(text, encoding="utf-8", errors="strict")
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')
def run(args, **opt): return subprocess.run(args, check=True, **opt)
def log(t): print(t, file=stderr)
def dumps(st, **opt): return json.dumps(st, sort_keys=True, **opt)
