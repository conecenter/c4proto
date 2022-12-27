
import subprocess
import json
import os
import uuid
import shutil
import argparse
import contextlib
from c4util import group_map, path_exists, read_json, sha256

def run(args, **opt):
    print("running: "+" ".join(args))
    return subprocess.run(args, check=True, **opt)

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

def copy_to_non_existed(from_path, to_path):
    if path_exists(to_path): never(f"{to_path} exists")
    shutil.copy(from_path, to_path)

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

def need_pod(name, get_opt):
    if not run_no_die(kcd_args("get","pod",name)): apply_manifest(construct_pod({ **get_opt(), "name": name }))
    wait_pod(name,60,("Running",))

def never(a): raise Exception(a)

def crane_login(push_secret):
    for registry, c in read_json(push_secret)["auths"].items():
        run(("crane","auth","login","-u",c["username"],"--password-stdin",registry), text=True, input=c["password"])

def build_cached_by_content(context, repository, push_secret):
    crane_login(push_secret)
    files = sorted(run(("find","-type","f"),cwd=context,text=True,capture_output=True).stdout.splitlines())
    sums = run(("sha256sum","--",*files),cwd=context,text=True,capture_output=True).stdout
    image = f"{repository}:c4b.{sha256(sums)[:8]}"
    if not run_no_die(("crane","manifest",image)):
        copy_to_non_existed(push_secret, f"{context}/.dockerconfigjson")
        with temp_dev_pod({ "image": "gcr.io/kaniko-project/executor:debug", "command": ["/busybox/sleep", "infinity"] }) as name:
            if not run_pipe_no_die(("tar","--exclude",".git","-C",context,"-czf-","."), kcd_args("exec","-i",name,"--","tar","-xzf-")):
                never("tar failed")
            kcd_run("exec",name,"--","mv",".dockerconfigjson","/kaniko/.docker/config.json")
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

def setup_parser(commands):
    main_parser = argparse.ArgumentParser()
    subparsers = main_parser.add_subparsers()
    for name, op, args in commands:
        parser = subparsers.add_parser(name)
        for a in args: parser.add_argument(a, required=True)
        parser.set_defaults(op=op)
    return main_parser

