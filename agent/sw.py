#!/usr/bin/python3 -u

from pathlib import Path
from sys import argv
from os import environ
from json import loads
from subprocess import run, DEVNULL

def write_text(path, text): Path(path).write_text(text, encoding="utf-8", errors="strict")
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')
def run_opt(args, key):
    proc = run(args, stdin=DEVNULL, text=True, capture_output=True)
    return loads(proc.stdout)[key] if proc.returncode == 0 else []

def main(proj):
    user = loads(read_text(f'{environ["HOME"]}/c4agent_public_state.json'))["devName"]
    label = f"app=de-{user}-{proj}-main"
    res, = [
        f'{ctx["name"]}~{pod["metadata"]["name"]}'
        for ctx in run_opt(("kubectl", "config", "view", "-o", "json"), "contexts")
        for pod in run_opt(("kubectl", "--context", ctx["name"], "get", "pods", "-l", label, "-o", "json"), "items")
    ]
    write_text("/tmp/c4pod", res)

main(*argv[1:])
