

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

def run(*args):
    subprocess.run(print_args(*args),check=True)

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

def build_image(opt):
    name = f"kaniko-{uuid.uuid4()}"
    try:
        apply_manifest(construct_pod({
            "name": name, "image": "gcr.io/kaniko-project/executor:debug", "command": ["/busybox/sleep", "infinity"],
        }))
        wait_pod(name,60,("Running",))
        with tempfile.TemporaryDirectory() as conf_dir:
            conf_path = f"{conf_dir}/config.json"
            if "/" not in opt.push_secret:
                cmd = ("kcd","get","secret",opt.push_secret,"-o","json")
                secret_str = subprocess.run(print_args(*cmd),check=True,text=True,capture_output=True).stdout
                secret_bytes = base64.b64decode(json.loads(secret_str)["data"][".dockerconfigjson"], validate=True)
                pathlib.Path(conf_path).write_bytes(secret_bytes)
            elif opt.push_secret[0] == "/":
                shutil.copy(opt.push_secret, conf_path)
            else: never(f"bad push secret: {opt.push_secret}")
            run("c4dsync","-ac",f"{conf_dir}/",f"{name}:/kaniko/.docker")
        run("c4dsync","-ac",f"{opt.context}/",f"{name}:/workspace")
        run("kcd","exec",name,"--","executor","--cache=true","-d",opt.image)
    finally:
        run("kcd","delete",f"pod/{name}")

def apply_manifest(manifest):
    manifest_str = json.dumps(manifest, sort_keys=True)
    subprocess.run(print_args("kcd","apply","-f-"),check=True,text=True,input=manifest_str)

def wait_pod(pod,timeout,phases):
    phase_lines = { f"{phase}\n": phase for phase in phases }
    args = print_args("kcd","get","pod",pod,"--watch","-o",'jsonpath={.status.phase}{"\\n"}',"--request-timeout",f"{timeout}s")
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


def compile(opt):
    pod = opt.name
    if subprocess.run(print_args("kcd","get","pod",pod)).returncode != 0: # todo more relevant condition
        with tempfile.TemporaryDirectory() as from_path_str:
            build_compiler_image(argparse.Namespace(context=from_path_str, image=opt.image, push_secret=opt.push_secret))
    apply_manifest(construct_pod({
        "name": pod, "image": opt.image, "imagePullSecrets": [{ "name": opt.pull_secret }],
    })) # todo to cpu node
    wait_pod(pod,60,("Running",))
    kex = ("kcd","exec",pod,"--")
    rsync = ("c4dsync","-avcr","--del")
    build_dir = opt.context
    mod_dir = f"{build_dir}/target/c4/mod.{opt.mod}.d"
    res_ff_path = f"{mod_dir}/c4res_files_from"
    cp_path = f"{mod_dir}/target/c4classpath"
    sync_paths_path = f"{mod_dir}/c4sync_paths_existing"
    full_sync_paths = (f"{build_dir}/{part}" for part in json.loads(read_text(f"{mod_dir}/c4sync_paths.json")))
    write_text(sync_paths_path, "\n".join(path for path in full_sync_paths if pathlib.Path(path).exists()))
    run(*rsync,"--files-from",sync_paths_path,f"/",f"{pod}:/")
    opt = os.environ["C4BUILD_JAVA_TOOL_OPTIONS"]
    run(*kex,"sh","-c",f"cd {mod_dir} && JAVA_TOOL_OPTIONS='{opt}' sbt c4build")
    run(*rsync,f"{pod}:{cp_path}",cp_path)
    write_text(res_ff_path, read_text(cp_path).replace(":","\n"))
    run(*rsync,"--files-from",res_ff_path,f"{pod}:/","/")

def write_text(path_str, text): pathlib.Path(path_str).write_text(text, encoding='utf-8', errors='strict')
def read_text(path_str): return pathlib.Path(path_str).read_text(encoding='utf-8', errors='strict')

def never(a): raise Exception(a)

#my $mem_repo_commits = sub{
#    my($dir)=@_;
#    my $content = join " ", sort map{
#        my $commit =
#            syf("git --git-dir=$_ rev-parse --short HEAD")=~/(\S+)/ ? $1 : die;
#        my $l_dir = m{^\./(|.*/)\.git$} ? $1 : die;
#        "$l_dir:$commit";
#    } syf("cd $dir && find -name .git")=~/(\S+)/g;
#    &$put_text(&$need_path("$dir/target/c4repo_commits"),$content);
#};

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
    )).parse_args()
    opt.op(opt)

main()
