from pathlib import Path
import subprocess
from os import environ
from sys import argv

def run(args, **opt): return subprocess.run(args, check=True, **opt)
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')

script, ag_path = argv
run(("rsync","-av",f'{ag_path}/generated/c4/', f'{environ["HOME"]}/'))
a_dir = str(Path(__file__).parent)
run(("pip","install", "-r", f"{a_dir}/requirements.txt"), env={**environ,"C4AGENT_DIR":a_dir})
run(("python3",f"{a_dir}/dev_docker_server.py"), env={**environ,"C4AGENT_DIR":a_dir})
