

import subprocess
import json
import os
import uuid
import tempfile
import shutil
import pathlib
import argparse
import contextlib
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

def run_pipe_no_die(from_args, to_args):
    from_proc = Popen(from_args, stdout=subprocess.PIPE)
    to_proc = Popen(to_args, stdin=from_proc.stdout)
    from_proc.wait()
    to_proc.wait()
    return from_proc.returncode == 0 and to_proc.returncode == 0

def path_exists(path):
    return pathlib.Path(path).exists()

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

def build_image(opt):
    copy_to_non_existed(opt.push_secret, f"{opt.context}/.dockerconfigjson")
    with temp_dev_pod({ "image": "gcr.io/kaniko-project/executor:debug", "command": ["/busybox/sleep", "infinity"] }) as name:
        if not run_pipe_no_die(("tar","-C",opt.context,"-czf-","."), kcd_args("exec","-i",name,"--","tar","-xzf-")):
            never("tar failed")
        kcd_run("exec",name,"--","mv",".dockerconfigjson","/kaniko/.docker/config.json")
        kcd_run("exec",name,"--","executor","--cache=true","-d",opt.image)

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

def need_pod(name, get_opt):
    if not run_no_die(kcd_args("get","pod",name)): apply_manifest(construct_pod({ **get_opt(), "name": name }))
    wait_pod(name,60,("Running",))

def sbt_args(mod_dir,java_opt):
    return ("env","-C",mod_dir,f"JAVA_TOOL_OPTIONS={java_opt}","sbt","c4build")

def get_cb_name(v): return f"cb-v0-{v}"
def get_more_compile_options(opt):
    return (f"{opt.context}/target/c4/mod.{opt.mod}.d",get_cb_name("cache"),f"/tmp/c4cache-{opt.commit}-{opt.proj_tag}")

def compile(opt):
    mod_dir, cache_pod_name, cache_path = get_more_compile_options(opt)
    pod = get_cb_name(f"u{opt.user}")
    cp_path = f"{mod_dir}/target/c4classpath"
    need_pod(pod, lambda: { "image": opt.image, **opt_compiler() })
    def init():
        if not run_no_die(kcd_args("exec",pod,"--","test","-e",mod_dir)):
            print("cache does not exist")
        elif not run_no_die(kcd_args("exec",pod,"--","rm","-r",mod_dir)):
            print("cache rm failed")
            return
        pipe_ok = run_pipe_no_die(
            kcd_args("exec",cache_pod_name,"--","cat",cache_path), kcd_args("exec","-i",pod,"--","tar","-C","/","-xzf-")
        )
        if not pipe_ok:
            print("cache get failed")
            return
        print("cache get ok")
    changing_text(f"{opt.context}/target/c4/compile_cache_ver", cache_path, init)
    full_sync_paths = (f"{opt.context}/{part}" for part in json.loads(read_text(f"{mod_dir}/c4sync_paths.json")))
    rsync(None, pod, [path for path in full_sync_paths if path_exists(path)])
    kcd_run("exec",pod,"--",*sbt_args(mod_dir,opt.java_options))
    rsync(pod, None, [cp_path])
    rsync(pod, None, read_text(cp_path).split(":"))

def compile_push(opt):
    mod_dir, cache_pod_name, cache_path = get_more_compile_options(opt)
    run(sbt_args(mod_dir,opt.java_options))
    need_pod(cache_pod_name, lambda: { "image": opt.image, **opt_compiler() }) # todo: opt_cache_node
    cache_tmp_path = f"{cache_path}-{uuid.uuid4()}"
    pipe_ok = run_pipe_no_die(
        ("tar","-C","/","-czf-",mod_dir), kcd_args("exec","-i",cache_pod_name,"--","sh","-c",f"cat > {cache_tmp_path}")
    )
    if not pipe_ok: never("cache put failed")
    run(kcd_args("exec",cache_pod_name,"--","mv",cache_tmp_path,cache_path))

def opt_sleep(): return { "command": ["sleep", "infinity"] }
def opt_pull_secret(): return { "imagePullSecrets": [{ "name": "c4pull" }] }
def opt_cpu_node(): return {
    "nodeSelector": { "c4builder": "true" },
    "tolerations": [{ "key": "c4builder", "operator": "Exists", "effect": "NoSchedule" }],
}
def opt_compiler(): return { **opt_pull_secret(), **opt_sleep(), **opt_cpu_node() }

def changing_text(path, will, then):
    if path_exists(path) and read_text(path) == will: return
    if then : then() # we need to run then() here -- if it fails, state will remain unchanged
    write_text(path, will)
def write_text(path_str, text): pathlib.Path(path_str).write_text(text, encoding='utf-8', errors='strict')
def read_text(path_str): return pathlib.Path(path_str).read_text(encoding='utf-8', errors='strict')

def never(a): raise Exception(a)

def build_de(opt):
    with tempfile.TemporaryDirectory() as context:
        data = "\n".join((f"FROM {opt.image}","ENTRYPOINT exec perl $C4CI_PROTO_DIR/sandbox.pl main"))
        write_text(f"{context}/Dockerfile", data)
        build_image(argparse.Namespace(context=context, image=f"{opt.image}.de", push_secret=opt.push_secret))

def build_rt(opt):
    with temp_dev_pod({ "image": opt.image, **opt_compiler() }) as name:
        remote_push_config = "/tmp/.c4-push-config"
        kcd_run("cp",opt.push_secret,f"{name}:{remote_push_config}")
        kcd_run("cp",os.environ["KUBECONFIG"],f"{name}:{get_remote_kube_config()}")
        rt_img = f"{opt.image}.{opt.proj_tag}.rt"
        kcd_run("exec", name, "--", "env", f"C4CI_BASE_TAG_ENV={opt.proj_tag}", *kcd_env(),
            f"C4COMMIT={opt.commit}", f"C4COMMON_IMAGE={opt.image}", f"C4BUILD_JAVA_TOOL_OPTIONS={opt.java_options}",
            "sh", "-c", "$C4STEP_BUILD"
        )
        kcd_run("exec", name, "--", "env", f"C4CI_BASE_TAG_ENV={opt.proj_tag}", "sh", "-c", "$C4STEP_BUILD_CLIENT")
        kcd_run("exec", name, "--", "env", f"C4CI_BASE_TAG_ENV={opt.proj_tag}", "sh", "-c", "$C4STEP_CP")
        kcd_run("exec", name, "--", "env", *kcd_env(), f"python3.8", "-u", f"{get_proto_dir()}/build_remote.py",
            "build_image", "--context", "/c4/res", "--image", rt_img, "--push-secret", remote_push_config
        )

def copy_image(opt):
    with temp_dev_pod({ "image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep() }) as name:
        kcd_run("cp",opt.push_secret,f"{name}:/tmp/auth.json")
        kcd_run("exec",name,"--","skopeo","copy",f"docker://{opt.from_image}",f"docker://{opt.to_image}")

@contextlib.contextmanager
def temp_dev_pod(opt):
    name = f"tb-{uuid.uuid4()}"
    apply_manifest(construct_pod({ **opt, "name": name }))
    try:
        wait_pod(name,60,("Running",))
        yield name
    finally:
        kcd_run("delete",f"pod/{name}")

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
        ('compile', compile, ("--commit","--proj-tag","--image","--context","--mod","--user","--java-options")),
        ('compile_push', compile_push, ("--commit","--proj-tag","--image","--context","--mod","--java-options")),
        ('build_image', build_image, ("--context","--image","--push-secret")),
        ('copy_image', copy_image, ("--from-image","--to-image","--push-secret")),
        ('build_de', build_de, ("--image","--push-secret")),
        ('build_rt', build_rt, ("--commit","--proj-tag","--image","--push-secret","--java-options")),
    )).parse_args()
    opt.op(opt)

main()
