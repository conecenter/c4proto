
import subprocess
import json
import os
import uuid
import argparse
import contextlib
import pathlib
import base64
import hashlib
from . import group_map, read_json, one, run, run_text_out, Popen, wait_processes, never, decode


def run_no_die(args, **opt):
    print("running: "+" ".join(args))
    return subprocess.run(args, **opt).returncode == 0


def run_pipe_no_die(from_args, to_args):
    from_proc = Popen(from_args, stdout=subprocess.PIPE)
    to_proc = Popen(to_args, stdin=from_proc.stdout)
    return wait_processes((from_proc, to_proc))


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


def crane_login(push_secret, repository):
    for registry, c in json.loads(push_secret)["auths"].items():
        if registry in repository:
            username, password = (
                (c["username"], c["password"]) if "username" in c and "password" in c else
                base64.b64decode(c["auth"]).decode(encoding='utf-8', errors='strict').split(":") if "auth" in c else
                never("bad auth")
            )
            run(("crane", "auth", "login", "-u", username, "--password-stdin", registry), text=True, input=password)


def crane_image_exists(image):
    res = run_no_die(("crane", "manifest", image), stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, text=True)
    print("image found" if res else "image not found")
    return res


def dir_sum(context, paths, args):
    files = sorted(run_text_out(("find", *paths, "-type", "f", *args), cwd=context).splitlines())
    sums = run_text_out(("sha256sum", "--", *files), cwd=context)
    return hashlib.sha256(sums.encode('utf-8')).hexdigest()


def build_cached_by_content(context, repository, push_secret_name):
    push_secret = secret_part_to_text(push_secret_name)
    crane_login(push_secret, repository)
    image = f"{repository}:c4b.{dir_sum(context, (), ())[:8]}"
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
    return generator, next(generator)


def setup_parser(commands):
    main_parser = argparse.ArgumentParser()
    subparsers = main_parser.add_subparsers(required=True)
    for name, op, args in commands:
        parser = subparsers.add_parser(name)
        for a in args: parser.add_argument(a, required=True)
        parser.set_defaults(op=op)
    return main_parser


def get_secret_data(secret_name):
    secret = json.loads(run_text_out(kcd_args("get", "secret", secret_name, "-o", "json")))
    return lambda secret_fn: base64.b64decode(secret["data"][secret_fn])
def secret_part_to_text(k8s_path):
    secret_name, secret_fn = k8s_path.split("/")
    return decode(get_secret_data(secret_name)(secret_fn))


def get_main_conf(context):
    main_conf = group_map(read_json(f"{context}/c4dep.main.json"), lambda it: (it[0], it[2]))
    return lambda k: one(*main_conf[k])


def get_proto(context, get_plain_option):
    proto_postfix = get_plain_option("C4PROTO_POSTFIX")
    proto_dir = f"{context}/{proto_postfix}"
    return proto_postfix, proto_dir


def get_image_conf(get_plain_option):
    repo = get_plain_option("C4CI_IMAGE_REPO")
    image_tag_prefix = get_plain_option("C4CI_IMAGE_TAG_PREFIX")
    return repo, image_tag_prefix

