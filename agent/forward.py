#!/usr/bin/python3 -u

from pathlib import Path
from subprocess import Popen, DEVNULL, STDOUT
from os import environ, kill
from signal import SIGTERM
from sys import stderr

for p in [p for p in Path("/proc").iterdir() if p.name.isdigit()]:
    line = (p/"cmdline").read_bytes().split(b'\x00')
    if line[0] == b'kubectl' and b'port-forward' in line: kill(int(p.name), SIGTERM)
kube_context, kind, pod_sel = Path("/tmp/c4pod").read_bytes().decode().split("~")
cmd = ("kubectl","--context",kube_context,"port-forward","--address",environ["C4AGENT_IP"],f'{kind}/{pod_sel}',"4005")
print(" ".join(cmd), file=stderr)
Popen(cmd,stdout=DEVNULL,stderr=STDOUT)
