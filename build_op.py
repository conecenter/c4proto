#!/usr/bin/python

import subprocess
import sys
import os
from tempfile import TemporaryDirectory
from pathlib import Path
from json import dumps, loads
from argparse import ArgumentParser


def run(args, **opt):
    print("running: " + " ".join(args), file=sys.stderr)
    return subprocess.run(args, check=True, **opt)


def read_json(path):
    return loads(Path(path).read_text(encoding="utf-8", errors="strict"))


def get_plain_options(plain_conf, k):
    return [line[2] for line in plain_conf if line[0] == k]


def handle_down(info):
    run(("helm", "delete", "--kube-context", info["context"], info["name"]))


def handle_up(info):
    dir = TemporaryDirectory()
    templates = [(f"{dir.name}/templates", f"{i}.yaml", d) for i, d in enumerate(info["manifests"])]
    chart = (dir.name, "Chart.yaml", {"apiVersion": "v2", "name": "c4chart", "version": "0"})
    for subdir, fn, j in [chart, *templates]:
        Path(subdir).mkdir(exist_ok=True)
        Path(f"{subdir}/{fn}").write_text(dumps(j, sort_keys=True), encoding="utf-8", errors="strict")
    run(("helm", "upgrade", "--install", "--wait", "--kube-context", info["context"], info["name"], dir.name))


def handle_prep(context, env_state, info_out):
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
    args = ("--context", dir_nm, "--env-state", env_state, "--info-out", info_out)
    run(("python3", "-u", f"{dir_nm}/{proto_postfix}/build_remote.py", "ci_prep", *args), env={
        "C4DEPLOY_CONTEXT": deploy_context,
        "PATH": os.environ["PATH"],
        "KUBECONFIG": os.environ["HOME"]+"/.kube/config"
    })


def main():
    main_parser = ArgumentParser()
    add_parser = main_parser.add_subparsers(required=True, dest="cmd").add_parser
    prep_parser = add_parser("prep")
    prep_parser.add_argument("--context", required=True)
    prep_parser.add_argument("--env-state", required=True)
    prep_parser.add_argument("--info-out", required=True)
    prep_parser.set_defaults(op=lambda: handle_prep(opt.context, opt.env_state, opt.info_out))
    up_parser = add_parser("up")
    up_parser.add_argument("info", type=read_json)
    up_parser.set_defaults(op=lambda: handle_up(opt.info))
    down_parser = add_parser("down")
    down_parser.add_argument("info", type=read_json)
    down_parser.set_defaults(op=lambda: handle_down(opt.info))
    opt = main_parser.parse_args()
    opt.op()


main()
