

import subprocess
import json
import os
import uuid
import tempfile
import shutil
import pathlib
import argparse
import base64
from c4util import group_map

def print_args(*args):
    print("running: "+" ".join(args))
    return args

def kcd_args(*args):
    return print_args("kubectl","--context",os.environ["C4DEPLOY_CONTEXT"],*args)

def kcd_run(*args):
    subprocess.run(kcd_args(*args),check=True)

def construct_pod(opt):
    option_group_rules = {
        "name": "metadata", "image": "container", "command": "container", "imagePullSecrets": "spec",
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
        cmd = kcd_args("get","secret",opt.push_secret,"-o","json")
        secret_str = subprocess.run(cmd,check=True,text=True,capture_output=True).stdout
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
        tar_proc = subprocess.Popen(print_args("tar","-czf-","."), stdout=subprocess.PIPE, cwd=opt.context)
        subprocess.run(kcd_args("exec","-i",name,"--","tar","-xzf-"), stdin=tar_proc.stdout)
        tar_proc.wait()
        if tar_proc.returncode != 0: never("tar failed")
        #
        kcd_run("exec",name,"--","mv",".dockerconfigjson","/kaniko/.docker/config.json")
        kcd_run("exec",name,"--","executor","--cache=true","-d",opt.image)
    finally:
        kcd_run("delete",f"pod/{name}")

def apply_manifest(manifest):
    manifest_str = json.dumps(manifest, sort_keys=True)
    subprocess.run(kcd_args("apply","-f-"),check=True,text=True,input=manifest_str)

def wait_pod(pod,timeout,phases):
    phase_lines = { f"{phase}\n": phase for phase in phases }
    args = kcd_args("get","pod",pod,"--watch","-o",'jsonpath={.status.phase}{"\\n"}',"--request-timeout",f"{timeout}s")
    with subprocess.Popen(args, stdout=subprocess.PIPE,text=True) as proc:
        for line in proc.stdout:
            print(line)
            if line in phase_lines:
                proc.kill()
                return phase_lines[line]
    raise Exception("pod waiting failed")

def get_proto_dir():
    return os.environ["C4CI_PROTO_DIR"]

def build_compiler_image(opt):
    shutil.copy(f"{get_proto_dir()}/install.pl", opt.context)
    data = "\n".join((
        "FROM ubuntu:22.04",
        "COPY install.pl /",
        "RUN perl install.pl useradd",
        "RUN perl install.pl apt curl ca-certificates rsync lsof",
        "RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz",
        "RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.7.1/sbt-1.7.1.tgz",
        "USER c4",
        "ENV PATH=${PATH}:/tools/jdk/bin:/tools/sbt/bin",
        "ENV JAVA_HOME=/tools/jdk",
        'ENTRYPOINT ["sleep","infinity"]',
    ))
    write_text(f"{opt.context}/Dockerfile", data)
    build_image(opt)

def rsync(*args):
    subprocess.run(print_args("c4dsync","-avcr","--del",*args),check=True)

def compile(opt):
    pod = opt.name
    if subprocess.run(kcd_args("get","pod",pod)).returncode != 0: # todo more relevant condition
        with tempfile.TemporaryDirectory() as from_path_str:
            build_compiler_image(argparse.Namespace(context=from_path_str, image=opt.image, push_secret=opt.push_secret))
    apply_wait_pod({
        "name": pod, "image": opt.image, "imagePullSecrets": [{ "name": opt.pull_secret }],
    }) # todo to cpu node
    build_dir = opt.context
    mod_dir = f"{build_dir}/target/c4/mod.{opt.mod}.d"
    res_ff_path = f"{mod_dir}/c4res_files_from"
    cp_path = f"{mod_dir}/target/c4classpath"
    sync_paths_path = f"{mod_dir}/c4sync_paths_existing"
    full_sync_paths = (f"{build_dir}/{part}" for part in json.loads(read_text(f"{mod_dir}/c4sync_paths.json")))
    write_text(sync_paths_path, "\n".join(path for path in full_sync_paths if pathlib.Path(path).exists()))
    rsync("--files-from",sync_paths_path,f"/",f"{pod}:/")
    opt = os.environ["C4BUILD_JAVA_TOOL_OPTIONS"]
    kcd_run("exec",pod,"--","sh","-c",f"cd {mod_dir} && JAVA_TOOL_OPTIONS='{opt}' sbt c4build")
    rsync(f"{pod}:{cp_path}",cp_path)
    write_text(res_ff_path, read_text(cp_path).replace(":","\n"))
    rsync("--files-from",res_ff_path,f"{pod}:/","/")

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
    name = f"cib-{opt.commit}-{opt.proj_tag}"
    kcd_run("create","secret","generic",name,"--from-file",f".dockerconfigjson={opt.push_secret}","--type","kubernetes.io/dockerconfigjson")
    apply_wait_pod({
        "name": name, "image": opt.image, "imagePullSecrets": [{ "name": name }], "command": ["sleep", "infinity"]
    }) # todo to cpu node
    remote_kube_config = "/tmp/.c4-kube-config"
    kcd_run("cp",os.environ["KUBECONFIG"],f"{name}:{remote_kube_config}")
    rt_img = f"{opt.image}.{opt.proj_tag}.rt"
    kcd_run("exec",name,"--","sh","-c",";".join((
        f"export C4CI_BASE_TAG_ENV={opt.proj_tag}",
        f"export KUBECONFIG={remote_kube_config}",
        f"export C4DEPLOY_CONTEXT={os.environ['C4DEPLOY_CONTEXT']}",
        " && ".join((
            "$C4STEP_BUILD", "$C4STEP_BUILD_CLIENT", "$C4STEP_CP",
            f"python3.8 -u $C4CI_PROTO_DIR/build_remote.py build_image --context /c4/res --image {rt_img} --push-secret {name}"
        ))
    )))

def copy_image(opt):
    name = f"skopeo-{uuid.uuid4()}"
    try:
        apply_wait_pod({
            "name": name, "image": "quay.io/skopeo/stable:v1.10.0", "command": ["sleep", "infinity"]
        })
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
        ('compile', compile, ("--name","--image","--pull-secret","--push-secret","--context","--mod")),
        ('build_image', build_image, ("--context","--image","--push-secret")),
        ('copy_image', copy_image, ("--from-image","--to-image","--push-secret")),
        ('build_de', build_de, ("--image","--push-secret")),
        ('build_rt', build_rt, ("--commit","--proj-tag","--image","--push-secret")),
    )).parse_args()
    opt.op(opt)

main()
