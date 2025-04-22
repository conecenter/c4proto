from pathlib import Path
import subprocess
from os import environ
from sys import argv

def run(args, **opt): return subprocess.run(args, check=True, **opt)
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')

script, ag_path = argv
run(("rsync","-av",f'{ag_path}/generated/c4/', f'{environ["HOME"]}/'))
a_dir = str(Path(__file__).parent)
run(("sbt","c4build"),cwd=a_dir)
run(("java","-cp",read_text(f"{a_dir}/target/c4classpath"),"Main"), env={**environ,"C4AGENT_DIR":a_dir})
