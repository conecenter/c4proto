
import json
import os
import typing
import uuid
import tempfile
import pathlib
import argparse
from c4util import path_exists, read_text, changing_text, read_json, one, \
    changing_text_observe
from build_util import never, run, run_no_die, run_pipe_no_die, Popen, wait_processes, need_dir, kcd_args, kcd_run, need_pod, temp_dev_pod, build_cached_by_content, setup_parser

def get_proto_dir():
    return os.environ["C4CI_PROTO_DIR"]

def rsync_args(from_pod, to_pod): return ("c4dsync","-acr","--del","--files-from","-",*(
    (f"{from_pod}:/","/") if from_pod and not to_pod else
    ("/",f"{to_pod}:/") if not from_pod and to_pod else never("bad args")
))

def rsync(from_pod, to_pod, files):
    run(rsync_args(from_pod, to_pod), text=True, input="\n".join(files))

def sbt_args(mod_dir,java_opt):
    return ("env","-C",mod_dir,f"JAVA_TOOL_OPTIONS={java_opt}","sbt","-Dsbt.color=true","c4build")

def get_cb_name(v): return f"cb-v0-{v}"

class CompileOptions(typing.NamedTuple):
    mod: str
    mod_dir: str
    cache_pod_name: str
    cache_path: str

def get_more_compile_options(context, commit, proj_tag):
    mod = read_json(f"{context}/target/c4/build.json")["tag_info"][proj_tag]["mod"]
    mod_dir = f"{context}/target/c4/mod.{mod}.d"
    cache_pod_name = get_cb_name("cache")
    cache_path = f"/tmp/c4cache-{commit}-{proj_tag}"
    return CompileOptions(mod, mod_dir, cache_pod_name, cache_path)

def compile(opt):
    compile_options = get_more_compile_options(opt.context, opt.commit, opt.proj_tag)
    mod_dir = compile_options.mod_dir
    cache_pod_name = compile_options.cache_pod_name
    cache_path = compile_options.cache_path
    pod = get_cb_name(f"u{opt.user}")
    cp_path = f"{mod_dir}/target/c4classpath"
    need_pod(pod, lambda: { "image": opt.image, **opt_compiler() })
    for save in changing_text_observe(f"{opt.context}/target/c4/compile_cache_ver", cache_path):
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
        save()
    full_sync_paths = (f"{opt.context}/{part}" for part in json.loads(read_text(f"{mod_dir}/c4sync_paths.json")))
    rsync(None, pod, [path for path in full_sync_paths if path_exists(path)])
    kcd_run("exec",pod,"--",*sbt_args(mod_dir,opt.java_options))
    rsync(pod, None, [cp_path])
    rsync(pod, None, read_text(cp_path).split(":"))

def push_compilation_cache(compile_options, image):
    mod_dir = compile_options.mod_dir
    cache_pod_name = compile_options.cache_pod_name
    cache_path = compile_options.cache_path
    need_pod(cache_pod_name, lambda: { "image": image, **opt_compiler() }) # todo: opt_cache_node
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

def crane_append(dir, base_image, target_image):
    #run(("ls","-la",from_dir))
    #("tar","-cf-","--exclude",".git",f"--transform",f"s,^,{to_dir}/,","--owner","c4","--group","c4","-C",from_dir,"."), # skips parent dirs, so bad grants on unpack
    run_pipe_no_die(("tar","-cf-","-C",dir,"."), ("crane","append","-f-","-b",base_image,"-t",target_image)) or never("crane append")

def build_image(opt):
    image = build_cached_by_content(opt.context, opt.repository, opt.push_secret)
    changing_text(opt.name_out, image)

def build_common(opt):
    proto_dir = get_proto_dir()
    pathlib.Path(f"{opt.context}/target").mkdir()
    run(("perl",f"{proto_dir}/sync_mem.pl",opt.context))
    build_dir = opt.build_dir
    with tempfile.TemporaryDirectory() as temp_root:
        proto_postfix = get_proto_postfix(opt.context)
        d_from_file = read_text(f"{proto_dir}/build.def.dockerfile")
        base_content = f"{d_from_file}\nENV C4CI_BUILD_DIR={build_dir}\nENV C4CI_PROTO_DIR={build_dir}/{proto_postfix}\n"
        changing_text(f"{temp_root}/Dockerfile", base_content)
        base_image = build_cached_by_content(temp_root, get_repo(opt.image), opt.push_secret)
    with tempfile.TemporaryDirectory() as temp_root:
        run(("perl",f"{proto_dir}/sync_setup.pl"), env={**os.environ,"HOME":temp_root})
        run(("rsync","-a","--exclude",".git",f"{opt.context}/", need_dir(f"{temp_root}{build_dir}"))) #shutil.copytree seems to be slower
        crane_append(temp_root, base_image, opt.image)
    with tempfile.TemporaryDirectory() as temp_root:
        changing_text(need_dir(f"{temp_root}/c4")+"/c4serve.pl", 'exec "perl","$ENV{C4CI_PROTO_DIR}/sandbox.pl","main";die')
        'exec "bash", @ARGV; die'
        crane_append(temp_root, opt.image, f"{opt.image}.de")

def build_rt(opt):
    with temp_dev_pod({ "image": opt.image, **opt_compiler() }) as name:
        remote_push_config = "/tmp/.c4-push-config"
        remote_kube_config = "/tmp/.c4-kube-config"
        kcd_env = (f"KUBECONFIG={remote_kube_config}",f"C4DEPLOY_CONTEXT={os.environ['C4DEPLOY_CONTEXT']}")
        kcd_run("cp",opt.push_secret,f"{name}:{remote_push_config}")
        kcd_run("cp",os.environ["KUBECONFIG"],f"{name}:{remote_kube_config}")
        kcd_run("exec", name, "--", "env", *kcd_env, f"python3.8", "-u", f"{get_proto_dir()}/build_remote.py",
            "build_rt_inner", "--context", opt.context, "--proj-tag", opt.proj_tag,
            "--commit", opt.commit, "--image", opt.image, "--java-options", opt.java_options,
            "--build-client", opt.build_client, "--push-secret", remote_push_config
        )

def build_rt_inner(opt):
    proto_dir = get_proto_dir()
    rt_img = f"{opt.image}.{opt.proj_tag}.rt"
    prod = ("perl",f"{proto_dir}/prod.pl")
    pre = ("python3.8", "-u", f"{proto_dir}/run_with_prefix.py")
    run(("perl",f"{proto_dir}/build.pl"), cwd=opt.context)
    compile_options = get_more_compile_options(opt.context, opt.commit, opt.proj_tag)  # after build.pl
    mod = compile_options.mod
    mod_dir = compile_options.mod_dir
    client_proc_opt = (Popen((*pre, "=client=", *prod, "build_client_changed", opt.context)),) if opt.build_client else ()  # after build.pl
    run(sbt_args(mod_dir,opt.java_options))
    run(("perl",f"{proto_dir}/build_env.pl", opt.context, mod))
    check_proc = Popen((*pre, "=check=", *prod, "ci_rt_chk", opt.context, mod))  # after build_env.pl
    push_compilation_cache(compile_options, opt.image)
    wait_processes((check_proc, *client_proc_opt)) or never("build failed")  # before ci_rt_base?
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_base", "--context", opt.context, "--proj-tag", opt.proj_tag, "--out-context", temp_root))
        base_image = build_cached_by_content(temp_root, get_repo(opt.image), opt.push_secret)
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_over", "--context", opt.context, "--proj-tag", opt.proj_tag, "--out-context", temp_root))
        crane_append(temp_root, base_image, rt_img)

def get_proto_postfix(context):
    proto_dir = get_proto_dir()
    proto_prefix, _, proto_postfix = proto_dir.rpartition("/")
    if proto_prefix != context: never(f"proto ({proto_dir}) out of context ({context})")
    return proto_postfix

def get_repo(image):
    res = image.rpartition(":")[0]
    if not res: never(image)
    return res

def build_gate(opt):
    proto_postfix = get_proto_postfix(opt.context)
    proto_dir = get_proto_dir()
    link = one(*(line for line in read_text(f"{opt.context}/c4dep.ci.replink").splitlines() if line.startswith(f"C4REL {proto_postfix}/")))
    build_rt(argparse.Namespace(
        image=opt.image, push_secret=opt.push_secret, java_options=opt.java_options,
        commit=link.split()[-1], proj_tag="def", context=proto_dir, build_client=""
    ))

def copy_image(opt):
    with temp_dev_pod({ "image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep() }) as name:
        kcd_run("cp",opt.push_secret,f"{name}:/tmp/auth.json")
        kcd_run("exec",name,"--","skopeo","copy",f"docker://{opt.from_image}",f"docker://{opt.to_image}")

def main():
    opt = setup_parser((
        ('build_common'  , build_common  , ("--context","--image","--push-secret","--build-dir")),
        ('build_gate'    , build_gate    , ("--context","--image","--push-secret","--java-options")),
        ('build_rt'      , build_rt      , ("--context","--image","--push-secret","--commit","--proj-tag","--java-options","--build-client")),
        ('build_rt_inner', build_rt_inner, ("--context","--image","--push-secret","--commit","--proj-tag","--java-options","--build-client")),
        ('build_image'   , build_image   , ("--context","--repository","--push-secret","--name-out")),
        ('compile'       , compile       , ("--context","--image","--user","--commit","--proj-tag","--java-options")),
        ('copy_image'    , copy_image    , ("--from-image","--to-image","--push-secret")),
    )).parse_args()
    opt.op(opt)

main()
