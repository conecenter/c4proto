#!/usr/bin/python3 -u

import subprocess
import sys
import os
from tempfile import TemporaryDirectory
from pathlib import Path
from json import loads
from argparse import ArgumentParser


def run(args, **opt):
    print("running: " + " ".join(args), file=sys.stderr)
    return subprocess.run(args, check=True, **opt)


def read_json(path):
    return loads(Path(path).read_text(encoding="utf-8", errors="strict"))


def get_plain_options(plain_conf, k):
    return [line[2] for line in plain_conf if line[0] == k]


def main():
    parser = ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--c4env", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--info-out", required=True)
    opt = parser.parse_args()
    context = opt.context
    plain_conf = read_json(f"{context}/c4dep.main.json")
    replink, = get_plain_options(plain_conf, "C4REPLINK")
    proto_postfix, = get_plain_options(plain_conf, "C4PROTO_POSTFIX")
    deploy_context, = get_plain_options(plain_conf, "C4DEPLOY_CONTEXT")
    dir_life = TemporaryDirectory()
    dir_nm = dir_life.name
    if os.environ.get("C4DEBUG_GIT"):
        run(("rsync", "-a", f"{context}/", f"{dir_nm}/"))
    else:
        run(("git", "clone", context, dir_nm))
    run(("/replink.pl",), env={"C4REPO_MAIN_CONF": f"{dir_nm}/{replink}"})
    args = ("--context", dir_nm, "--c4env", opt.c4env, "--state", opt.state, "--info-out", opt.info_out)
    run(("python3", "-u", f"{dir_nm}/{proto_postfix}/build_remote.py", "ci_prep", *args), env={
        "C4DEPLOY_CONTEXT": deploy_context, "PATH": os.environ["PATH"], "KUBECONFIG": os.environ["KUBECONFIG"]
    })

main()
