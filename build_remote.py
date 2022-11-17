

import subprocess
import json
import os
import uuid
import tempfile
import shutil
import pathlib
import argparse
import base64
from c4util import group_map, one

def run(args, **opt):
    print("running: "+" ".join(args))
    return subprocess.run(args, check=True, **opt)

def run_no_die(args, **opt):
    print("running: "+" ".join(args))
    return subprocess.run(args, **opt).returncode == 0

def Popen(args, **opt):
    print("starting: "+" ".join(args))
    return subprocess.Popen(args, **opt)

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

def apply_wait_pod(opt):
    apply_manifest(construct_pod(opt))
    wait_pod(opt["name"],60,("Running",))

def build_image(opt):
    conf_path = pathlib.Path(f"{opt.context}/.dockerconfigjson")
    if conf_path.exists(): never(f"{conf_path} exists")
    if "/" not in opt.push_secret:
        secret_str = run(kcd_args("get","secret",opt.push_secret,"-o","json"),text=True,capture_output=True).stdout
        secret_bytes = base64.b64decode(json.loads(secret_str)["data"][".dockerconfigjson"], validate=True)
        conf_path.write_bytes(secret_bytes)
    elif opt.push_secret[0] == "/": shutil.copy(opt.push_secret, conf_path)
    else: never(f"bad push secret: {opt.push_secret}")
    #
    name = f"kaniko-{uuid.uuid4()}"
    try:
        apply_wait_pod({
            "name": name, "image": "gcr.io/kaniko-project/executor:debug", "command": ["/busybox/sleep", "infinity"],
        })
        #
        tar_proc = Popen(("tar","-czf-","."), stdout=subprocess.PIPE, cwd=opt.context) # tar -C can replace cwd
        run(kcd_args("exec","-i",name,"--","tar","-xzf-"), stdin=tar_proc.stdout)
        tar_proc.wait()
        if tar_proc.returncode != 0: never("tar failed")
        #
        kcd_run("exec",name,"--","mv",".dockerconfigjson","/kaniko/.docker/config.json")
        kcd_run("exec",name,"--","executor","--cache=true","-d",opt.image)
    finally:
        kcd_run("delete",f"pod/{name}")

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

def get_proto_dir():
    return os.environ["C4CI_PROTO_DIR"]

def rsync_args(from_pod, to_pod): return ("c4dsync","-acr","--del","--files-from","-",*(
    (f"{from_pod}:/","/") if from_pod and not to_pod else
    ("/",f"{to_pod}:/") if not from_pod and to_pod else never("bad args")
))

def rsync(from_pod, to_pod, files):
    run(rsync_args(from_pod, to_pod), text=True, input="\n".join(files))

def get_remote_kube_config(): return "/tmp/.c4-kube-config"

def kcd_env():
    return (f"KUBECONFIG={get_remote_kube_config()}",f"C4DEPLOY_CONTEXT={os.environ['C4DEPLOY_CONTEXT']}")

def compile(opt):
    pod = f"cib-{opt.commit}-u{opt.user}-{opt.proj_tag}"
    build_dir = opt.context
    mod_dir = f"{build_dir}/target/c4/mod.{opt.mod}.d"
    cp_path = f"{mod_dir}/target/c4classpath"
    pod_existed = run_no_die(kcd_args("get","pod",pod))
    if not pod_existed:
        ci_name = f"cib-{opt.commit}-ci-{opt.proj_tag}"
        ci_pod_str = run(kcd_args("get","pod","-o","json",ci_name),text=True,capture_output=True).stdout
        image = one(*json.loads(ci_pod_str)["spec"]["containers"])["image"]
        apply_wait_pod({ "name": pod, "image": image, **opt_pull_secret(ci_name), **opt_sleep(), **opt_cpu_node() })
        run(kcd_args("exec", "-i", ci_name, "--", "env", *kcd_env(), *rsync_args(None, pod)), text=True, input=mod_dir)
    full_sync_paths = (f"{build_dir}/{part}" for part in json.loads(read_text(f"{mod_dir}/c4sync_paths.json")))
    rsync(None, pod, [path for path in full_sync_paths if pathlib.Path(path).exists()])
    java_opt = os.environ["C4BUILD_JAVA_TOOL_OPTIONS"]
    kcd_run("exec",pod,"--","sh","-c",f"cd {mod_dir} && JAVA_TOOL_OPTIONS='{java_opt}' sbt c4build")
    rsync(pod, None, [cp_path])
    rsync(pod, None, read_text(cp_path).split(":"))

def opt_sleep(): return { "command": ["sleep", "infinity"] }
def opt_pull_secret(v): return { "imagePullSecrets": [{ "name": v }] }
def opt_cpu_node(): return {
    "nodeSelector": { "c4builder": "true" },
    "tolerations": [{ "key": "c4builder", "operator": "Exists", "effect": "NoSchedule" }],
}

def write_text(path_str, text): pathlib.Path(path_str).write_text(text, encoding='utf-8', errors='strict')
def read_text(path_str): return pathlib.Path(path_str).read_text(encoding='utf-8', errors='strict')

def never(a): raise Exception(a)

def build_de(opt):
    with tempfile.TemporaryDirectory() as context:
        data = "\n".join((f"FROM {opt.image}","ENTRYPOINT exec perl $C4CI_PROTO_DIR/sandbox.pl main"))
        write_text(f"{context}/Dockerfile", data)
        build_image(argparse.Namespace(context=context, image=f"{opt.image}.de", push_secret=opt.push_secret))

def build_rt(opt):
    #todo prevented double?
    name = f"cib-{opt.commit}-ci-{opt.proj_tag}"
    kcd_run("create","secret","generic",name,"--from-file",f".dockerconfigjson={opt.push_secret}","--type","kubernetes.io/dockerconfigjson")
    apply_wait_pod({ "name": name, "image": opt.image, **opt_pull_secret(name), **opt_sleep(), **opt_cpu_node() })
    remote_kube_config = get_remote_kube_config()
    kcd_run("cp",os.environ["KUBECONFIG"],f"{name}:{remote_kube_config}")
    rt_img = f"{opt.image}.{opt.proj_tag}.rt"
    kcd_run("exec",name,"--","env",*kcd_env(),f"C4CI_BASE_TAG_ENV={opt.proj_tag}","sh","-c"," && ".join((
        "$C4STEP_BUILD", "$C4STEP_BUILD_CLIENT", "$C4STEP_CP",
        f"python3.8 -u $C4CI_PROTO_DIR/build_remote.py build_image --context /c4/res --image {rt_img} --push-secret {name}"
    )))

def copy_image(opt):
    name = f"skopeo-{uuid.uuid4()}"
    try:
        apply_wait_pod({ "name": name, "image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep() })
        kcd_run("cp",opt.push_secret,f"{name}:/tmp/auth.json")
        kcd_run("exec",name,"--","skopeo","copy",f"docker://{opt.from_image}",f"docker://{opt.to_image}")
    finally:
        kcd_run("delete",f"pod/{name}")


#todo: no ci_build_aggr C4CI_CAN_FAIL .aggr
#todo: .de "ENV C4CI_BASE_TAG_ENV=$proj_tag", "ENTRYPOINT exec perl \$C4CI_PROTO_DIR/sandbox.pl main",


def setup_parser(commands):
    main_parser = argparse.ArgumentParser()
    subparsers = main_parser.add_subparsers()
    for name, op, args in commands:
        parser = subparsers.add_parser(name)
        for a in args: parser.add_argument(a, required=True)
        parser.set_defaults(op=op)
    return main_parser

def main():
    opt = setup_parser((
        ('compile', compile, ("--commit","--proj-tag","--user","--context","--mod")),
        ('build_image', build_image, ("--context","--image","--push-secret")),
        ('copy_image', copy_image, ("--from-image","--to-image","--push-secret")),
        ('build_de', build_de, ("--image","--push-secret")),
        ('build_rt', build_rt, ("--commit","--proj-tag","--image","--push-secret")),
    )).parse_args()
    opt.op(opt)

main()
