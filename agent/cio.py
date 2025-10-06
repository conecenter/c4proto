#!/usr/bin/python3 -u

from subprocess import check_output
from sys import argv, stderr

def main(deploy_context, arg):
    kc = ("kubectl", "--context", deploy_context)
    pod = "svc/c4cio"
    res = check_output((*kc, "exec", pod, "--", "python3", "-u", "/ci_serve.py", f'[["call",{arg}]]')).decode().split()
    log_cmd = " ".join((*kc, "logs", pod, "-f", "--timestamps", "--tail", "1000"))
    grep = f" | grep {res[0]}" if len(res) == 1 else ""
    print(f"### to view logs:\n{log_cmd}{grep}", file=stderr)

main(*argv[1:])