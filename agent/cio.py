#!/usr/bin/python3 -u

import subprocess
from sys import argv, stderr

def run(args, **opt): return subprocess.run(args, check=True, **opt)

def main(deploy_context, arg):
    kc = ("kubectl", "--context", deploy_context)
    pod = "svc/c4cio"
    run((*kc, "exec", pod, "--", "python3", "-u", "/ci_serve.py", f'[["call",{arg}]]'))
    print("### to view logs:\n" + " ".join((*kc, "logs", pod, "-f", "--timestamps", "--tail", "1000")), file=stderr)

main(*argv[1:])