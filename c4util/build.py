
import subprocess
import json
import os
import uuid
import argparse
import contextlib
import time
import pathlib
import base64
import typing
from . import group_map, read_json, sha256, one

def run(args, **opt):
    print("running: " + " ".join(args))
    started = time.monotonic()
    res = subprocess.run(args, check=True, **opt)
    print(f"{time.monotonic() - started}s for {args[0]}")
    return res

# def run(args, **opt):
#     print("running: "+" ".join(args))
#     return subprocess.run(args, check=True, **opt)

def run_no_die(args, **opt):
    print("running: "+" ".join(args))
    return subprocess.run(args, **opt).returncode == 0

def Popen(args, **opt):
    print("starting: "+" ".join(args))
    return subprocess.Popen(args, **opt)

def wait_processes(processes):
    for proc in processes:
        proc.wait()
    return all(proc.returncode == 0 for proc in processes)

def run_pipe_no_die(from_args, to_args):
    from_proc = Popen(from_args, stdout=subprocess.PIPE)
    to_proc = Popen(to_args, stdin=from_proc.stdout)
    return wait_processes((from_proc, to_proc))

def run_text_out(args, **opt):
    print("running: "+" ".join(args))
    return subprocess.run(args, check=True, text=True, capture_output=True, **opt).stdout

def need_dir(dir):
    pathlib.Path(dir).mkdir(parents=True, exist_ok=True)
    return dir

def kcd_args(*args):
    return ("kubectl","--context",os.environ["C4DEPLOY_CONTEXT"],*args)

def kcd_run(*args):
    run(kcd_args(*args))

def construct_pod(opt):
    option_group_rules = {
        "name": "metadata", "image": "container", "command": "container",
        "imagePullSecrets": "spec", "nodeSelector": "spec", "tolerations": "spec"
    }
    groups = group_map(opt.items(), lambda it: (option_group_rules[it[0]],it))
    return { "apiVersion": "v1", "kind": "Pod", "metadata": dict(groups["metadata"]), "spec": {
        "containers": [{
            "name": "main", "securityContext": { "allowPrivilegeEscalation": False }, **dict(groups["container"])
        }],
        **dict(groups.get("spec") or [])
    }}

def apply_manifest(manifest):
    run(kcd_args("apply","-f-"), text=True, input=json.dumps(manifest, sort_keys=True))

def wait_pod(pod,timeout,phases):
    phase_lines = { f"{phase}\n": phase for phase in phases }
    args = kcd_args("get","pod",pod,"--watch","-o",'jsonpath={.status.phase}{"\\n"}',"--request-timeout",f"{timeout}s")
    with Popen(args, stdout=subprocess.PIPE,text=True) as proc:
        for line in proc.stdout:
            print(line)
            if line in phase_lines:
                proc.kill()
                return phase_lines[line]
    never("pod waiting failed")

# need_pod: image/versions/attrs need to be encoded in name
def need_pod(name, get_opt):
    if not run_no_die(kcd_args("get","pod",name)): apply_manifest(construct_pod({ **get_opt(), "name": name }))
    wait_pod(name,60,("Running",))

def never(a): raise Exception(a)

def crane_login(push_secret):
    for registry, c in json.loads(push_secret)["auths"].items():
        username, password = (
            (c["username"],c["password"]) if "username" in c and "password" in c else
            base64.b64decode(c["auth"]).decode(encoding='utf-8', errors='strict').split(":") if "auth" in c else
            never("bad auth")
        )
        run(("crane","auth","login","-u",username,"--password-stdin",registry), text=True, input=password)


def crane_image_exists(image):
    return run_no_die(("crane", "manifest", image))


def build_cached_by_content(context, repository, push_secret_name):
    push_secret = secret_part_to_text(push_secret_name)
    crane_login(push_secret)
    files = sorted(run_text_out(("find","-type","f"),cwd=context).splitlines())
    sums = run_text_out(("sha256sum","--",*files),cwd=context)
    image = f"{repository}:c4b.{sha256(sums)[:8]}"
    if not crane_image_exists(image):
        with temp_dev_pod({ "image": "gcr.io/kaniko-project/executor:debug", "command": ["/busybox/sleep", "infinity"] }) as name:
            if not run_pipe_no_die(("tar","--exclude",".git","-C",context,"-czf-","."), kcd_args("exec","-i",name,"--","tar","-xzf-")):
                never("tar failed")
            run(kcd_args("exec","-i",name,"--","sh","-c","cat > /kaniko/.docker/config.json"), text=True, input=push_secret)
            kcd_run("exec",name,"--","executor","--cache=true","-d",image)
    return image

@contextlib.contextmanager
def temp_dev_pod(opt):
    name = f"tb-{uuid.uuid4()}"
    apply_manifest(construct_pod({ **opt, "name": name }))
    try:
        wait_pod(name,60,("Running",))
        yield name
    finally:
        kcd_run("delete","--wait=false",f"pod/{name}")


def _temp_dev_pod_generator(opt):
    name = f"tb-{uuid.uuid4()}"
    apply_manifest(construct_pod({**opt, "name": name}))
    try:
        wait_pod(name, 60, ("Running",))
        yield name
    finally:
        kcd_run("delete", "--wait=false", f"pod/{name}")


def get_temp_dev_pod(opt):
    generator = _temp_dev_pod_generator(opt)
    (generator, next(generator))


def setup_parser(commands):
    main_parser = argparse.ArgumentParser()
    subparsers = main_parser.add_subparsers(required=True)
    for name, op, args in commands:
        parser = subparsers.add_parser(name)
        for a in args: parser.add_argument(a, required=True)
        parser.set_defaults(op=op)
    return main_parser

def decode(bs): return bs.decode(encoding='utf-8', errors='strict')

def get_secret_data(secret_name):
    secret = json.loads(run_text_out(kcd_args("get", "secret", secret_name, "-o", "json")))
    return lambda secret_fn: base64.b64decode(secret["data"][secret_fn])
def secret_part_to_text(k8s_path):
    secret_name, secret_fn = k8s_path.split("/")
    return decode(get_secret_data(secret_name)(secret_fn))


def get_env_values_from_deployments(env_key, deployments):
    return {
        e["value"] for d in deployments for c in d["spec"]["template"]["spec"]["containers"]
        for e in c.get("env",[]) if e["name"] == env_key
    }

def get_main_conf(context):
    main_conf = group_map(read_json(f"{context}/c4dep.main.json"), lambda it: (it[0], it[1:]))
    return lambda k: one(*main_conf[k])[2]
