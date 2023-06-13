#!/usr/bin/python3 -u

from json import dumps
import base64
import sys
import subprocess
from os import environ as e
from pathlib import Path


def debug(text):
    print(text, file=sys.stderr)


def run(args, **opt):
    debug("running: " + " ".join(args))
    return subprocess.run(args, check=True, **opt)


def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')


def replink(dir, fn):
    run(("/replink.pl",), env={"C4CI_BUILD_DIR": dir, "C4REPO_MAIN_CONF": f"{dir}/{fn}"})


def handle_build_common(script, op, *args):
    opt = dict(zip(args[::2], args[1::2]))
    project_dir = e["CI_PROJECT_DIR"]
    proto_dir = e["C4COMMON_PROTO_DIR"]
    replink(project_dir, "c4dep.ci.replink")
    replink(proto_dir, "c4dep.main.replink")
    run((
        "python3", "-u", f"{proto_dir}/run_with_prefix.py", "time",
        "python3", "-u", f"{proto_dir}/build_remote.py", "build_common",
        "--build-dir", e["C4CI_BUILD_DIR"], "--push-secret", opt["--push-secret"], "--context", project_dir,
        "--image", e["C4COMMON_IMAGE"], "--commit", e["CI_COMMIT_SHORT_SHA"],
        "--java-options", e["C4BUILD_JAVA_TOOL_OPTIONS"]
    ), env={**e, "C4CI_PROTO_DIR": proto_dir})


def handle_generate(script, build_path):
    script_body = read_text(script)
    script_body_encoded = base64.b64encode(script_body.encode('utf-8')).decode('utf-8')
    cmd = f"echo '{script_body_encoded}' | base64 -d > /tools/c4ci && chmod +x /tools/c4ci"
    variables = {"C4PYTHON": "/usr/bin/python3", "C4COMMON_BUILDER_INSTALL_CMD": cmd}
    out = {".build_common": {"image": "ghcr.io/conecenter/c4replink:v3kc", "variables": variables}}
    out_path = f"{build_path}/gitlab-ci-generated.yml"
    Path(out_path).write_text(dumps(out, sort_keys=True, indent=4), encoding='utf-8', errors='strict')


handle = {".": handle_generate, "build_common": handle_build_common}
handle[sys.argv[1]](*sys.argv)
