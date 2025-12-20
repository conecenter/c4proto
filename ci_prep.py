#!/usr/bin/python3 -u
from base64 import b64decode
from subprocess import check_output
from os import environ
from sys import stderr
from tempfile import TemporaryDirectory
from pathlib import Path
from json import loads, dumps
from argparse import ArgumentParser

def da(*args):
    print("running: " + " ".join(args), file=stderr)
    return args

def ci(nm): return str(Path(__file__).parent.parent / "ci" / nm)

def make_manifests(c4env, part):
    manifests = loads(check_output(da("perl", str(ci("make_manifests.pl")), dumps(part))))
    has_env, = {man["metadata"]["labels"]["c4env"] for man in manifests}
    if c4env != has_env: raise Exception("bad c4env name")
    out = {"kube-context": part["context"], "manifests": manifests, "c4env": c4env}
    return dumps(out, sort_keys=True, indent=4).encode()

def ci_prep(kube_context, context, c4env, state, info_out):
    secret = loads(check_output(da("kubectl", "--context", kube_context, "get", "secret", "c4dconf-pl", "-o", "json")))
    temp_root = TemporaryDirectory()
    conf_path = f"{temp_root.name}/main.pl"
    Path(conf_path).write_bytes(b64decode(secret["data"]["main.pl"]))
    info_args = ("--conf", conf_path, "--env-state", f"{c4env}-{state}")
    part = loads(check_output(da("perl", str(ci("ci_deploy_info.pl")), *info_args)))
    build = ("python3", "-u", str(ci("ci_build.py")))
    args = ("--kube-context", kube_context, "--context", context, "--opt", dumps(part))
    image = check_output(da(*build, *args)).decode().strip()
    Path(info_out).write_bytes(make_manifests(c4env, {**part, "image": image}))

# uses env PATH C4DEPLOY_CONTEXT KUBECONFIG
def main():
    parser = ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--c4env", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--info-out", required=True)
    ci_prep(environ["C4DEPLOY_CONTEXT"], **vars(parser.parse_args()))

main()
