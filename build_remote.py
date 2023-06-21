
import json
import os
import sys
import time
import typing
import uuid
import tempfile
import pathlib
import re
from c4util import path_exists, read_text, changing_text, read_json, changing_text_observe
from c4util.build import never, run, run_no_die, run_pipe_no_die, Popen, \
    wait_processes, need_dir, kcd_args, kcd_run, need_pod, get_main_conf, get_temp_dev_pod, \
    build_cached_by_content, setup_parser, secret_part_to_text, run_text_out, crane_image_exists


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
    java_options: str

def get_more_compile_options(context, commit, proj_tag):
    build_conf = read_json(f"{context}/target/c4/build.json")
    mod = build_conf["tag_info"][proj_tag]["mod"]
    java_options = " ".join((line[2] for line in build_conf["plain"] if line[0] == "C4BUILD_JAVA_TOOL_OPTIONS"))
    mod_dir = f"{context}/target/c4/mod.{mod}.d"
    cache_pod_name = get_cb_name("cache")
    cache_path = f"/tmp/c4cache-{commit}-{proj_tag}"
    return CompileOptions(mod, mod_dir, cache_pod_name, cache_path, java_options)


def compile(opt):
    commit = get_commits(opt.context)[""]
    compile_options = get_more_compile_options(opt.context, commit, opt.proj_tag)
    mod_dir = compile_options.mod_dir
    cache_pod_name = compile_options.cache_pod_name
    cache_path = compile_options.cache_path
    pod = get_cb_name(f"u{opt.user}")
    cp_path = f"{mod_dir}/target/c4classpath"
    need_pod(pod, lambda: { "image": opt.image, **opt_compiler() })
    for save in changing_text_observe(f"{opt.context}/target/c4/compile_cache_ver", cache_path):
        if not run_no_die(kcd_args("exec",pod,"--","test","-e",mod_dir)):
            print("private cache does not exist")
        elif not run_no_die(kcd_args("exec",pod,"--","rm","-r",mod_dir)):
            print("private cache rm failed")
            break
        if not run_no_die(kcd_args("exec",cache_pod_name,"--","test","-e",cache_path)):
            print("shared cache does not exist")
        else:
            pipe_ok = run_pipe_no_die(
                kcd_args("exec",cache_pod_name,"--","cat",cache_path), kcd_args("exec","-i",pod,"--","tar","-C","/","-xzf-")
            )
            if not pipe_ok:
                print("cache get failed")
                break
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
    changing_text(opt.name_out, build_cached_by_content(opt.context, opt.repository, opt.push_secret_from_k8s))


def get_commits(context):
    return dict(re.findall("(\\S*):(\\S*)", read_text(f"{context}/target/c4repo_commits")))


def cp_kube_conf(name):
    remote_conf = "/tmp/.c4-kube-config"
    kcd_run("cp", os.environ["KUBECONFIG"], f"{name}:{remote_conf}")
    return "env", f"KUBECONFIG={remote_conf}"


def info(text):
    print(text, file=sys.stderr)


def get_common_image_name(get_plain_option, commit):
    image_repo = get_plain_option("C4CI_IMAGE_REPO")
    image_prefix = get_plain_option("C4CI_IMAGE_TAG_PREFIX")
    return image_repo, f"{image_prefix}.{commit}", f"{image_repo}:{image_prefix}.{commit}"


def ci_prep(context, env_state, info_out):
    started = time.monotonic()
    temp_root_life = tempfile.TemporaryDirectory()
    temp_root = temp_root_life.name
    info("reading general build settings ...")
    get_plain_option = get_main_conf(context)
    proto_postfix = get_plain_option("C4PROTO_POSTFIX")
    deploy_context = get_plain_option("C4DEPLOY_CONTEXT")
    build_dir = get_plain_option("C4CI_BUILD_DIR")
    proto_dir = f"{context}/{proto_postfix}"
    remote_proto_dir = f"{build_dir}/{proto_postfix}"
    info("getting commit info ...")
    pathlib.Path(f"{context}/target").mkdir()
    run(("perl", f"{proto_dir}/sync_mem.pl", context))
    commit = get_commits(context)['']  # after sync_mem
    image_repo, image_tag_prefix, image = get_common_image_name(get_plain_option, commit)
    push_secret_name = "c4push/.dockerconfigjson"
    info("making base builder image ...")
    changing_text(need_dir(f"{temp_root}/base")+"/Dockerfile", "\n".join((
        read_text(f"{proto_dir}/build.def.dockerfile"),
        f"ENV C4DEPLOY_CONTEXT={deploy_context}",
        f"ENV C4CI_BUILD_DIR={build_dir}",
        f"ENV C4CI_PROTO_DIR={remote_proto_dir}",
    )))
    base_image = build_cached_by_content(f"{temp_root}/base", image_repo, push_secret_name)
    if not crane_image_exists(image):
        info("making dev images ...")
        run(("perl", f"{proto_dir}/sync_setup.pl"), env={**os.environ, "HOME": f"{temp_root}/common"})
        run(("rsync", "-a", "--exclude", ".git", f"{context}/", need_dir(f"{temp_root}/common{build_dir}")))
        # shutil.copytree seems to be slower
        crane_append(f"{temp_root}/common", base_image, image)
        changing_text(
            need_dir(f"{temp_root}/de/c4")+"/c4serve.pl",
            "\n".join('#!/usr/bin/perl', 'use strict;', 'exec "perl","$ENV{C4CI_PROTO_DIR}/sandbox.pl","main";', 'die;')
        )
        crane_append(f"{temp_root}/de", image, f"{image}.de")
    info("making deploy info ...")
    pod_life, name = get_temp_dev_pod({"image": image, **opt_compiler()})
    out_path = "/tmp/.c4-out"
    cmd = ("perl", f"{remote_proto_dir}/prod.pl", "ci_deploy_info", "--env-state", env_state, "--out", out_path)
    kcd_run("exec", name, "--", *cp_kube_conf(name), *cmd)
    deploy_info = json.loads(run_text_out(kcd_args("exec", name, "--", "cat", out_path)))
    to_repo = deploy_info["to_repo"]
    parts = [
        {
            **part, "image_type": image_type,
            "from_image": f"{image_repo}:{image_tag}", "image": f"{to_repo or image_repo}:{image_tag}",
        }
        for part in deploy_info["parts"] for image_type in [part.get("image_type", "rt")]
        for image_tag in [
            f"{image_tag_prefix}.{part['project']}.{image_type}" if image_type == "rt" else
            f"{image_tag_prefix}.{image_type}" if image_type == "de" else
            never(image_type)
        ]
    ]
    cmd = ("perl", f"{remote_proto_dir}/make_manifests.pl", "--values", json.dumps(parts), "--out", out_path)
    kcd_run("exec", name, "--", *cmd)
    manifests = [json.loads(t) for t in run_text_out(kcd_args("exec", name, "--", "cat", out_path)).split("\n")]
    env_url = next(sorted(f"https://{h}" for c in parts for h in [c["ci:hostname"]] if h), "")
    out = {"manifests": manifests, "url": env_url, **{k: deploy_info[k] for k in ("name", "group", "context")}}
    changing_text(info_out, out)
    info("starting remote build tasks for runtime images ...")
    build_parts = [part for part in parts if part["image_type"] == "rt" and not crane_image_exists(part["from_image"])]
    build_pods = [(part, *get_temp_dev_pod({"image": image, **opt_compiler()})) for part in build_parts]
    for part, pod_life, name in build_pods:
        cm = ("python3.8", "-u", f"{remote_proto_dir}/build_remote.py", "build_rt_inner", "--proj-tag", part["project"])
        Popen(kcd_args("exec", name, "--", *cp_kube_conf(name), *cm))  # we will wait images instead
    info("waiting images ...")
    for part in parts:
        while not crane_image_exists(part["from_image"]):
            if time.monotonic() - started > 60*30:
                never("timeout")
            time.sleep(5)
    info("coping images to external registry ...")
    copy_parts = [part for part in parts if (
        False if part["from_image"] == part["image"] else
        never("source deploy to alien repo is not allowed") if part["image_type"] != "rt" else
        not crane_image_exists(part["image"])
    )]
    if copy_parts:
        push_secret = secret_part_to_text(push_secret_name)
        pod_life, name = get_temp_dev_pod({"image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep()})
        run(kcd_args("exec", "-i", name, "--", "sh", "-c", "cat > /tmp/auth.json"), text=True, input=push_secret)
        for part in copy_parts:
            kcd_run("exec", name, "--", "skopeo", "copy", f"docker://"+part["from_image"], f"docker://"+part["image"])


def build_rt_inner(context, proj_tag):
    get_plain_option = get_main_conf(context)
    proto_postfix = get_plain_option("C4PROTO_POSTFIX")
    proto_dir = f"{context}/{proto_postfix}"
    commit = get_commits(context)[""]
    image_repo, image_tag_prefix, image = get_common_image_name(get_plain_option, commit)
    prod = ("perl", f"{proto_dir}/prod.pl")
    pre = ("python3.8", "-u", f"{proto_dir}/run_with_prefix.py")
    run(("perl", f"{proto_dir}/build.pl"), cwd=context)
    client_proc_opt = (Popen((*pre, "=client=", *prod, "build_client_changed", context)),) if proj_tag != "def" else ()  # after build.pl
    rt_img = f"{image}.{proj_tag}.rt"
    compile_options = get_more_compile_options(context, commit, proj_tag)  # after build.pl
    mod = compile_options.mod
    mod_dir = compile_options.mod_dir
    run((*pre, f"={proj_tag}=", *sbt_args(mod_dir, compile_options.java_options)))
    run(("perl", f"{proto_dir}/build_env.pl", context, mod))
    check_proc = Popen((*pre, "=check=", *prod, "ci_rt_chk", context, mod))  # after build_env.pl
    push_compilation_cache(compile_options, image)
    wait_processes((check_proc, *client_proc_opt)) or never("build failed")  # before ci_rt_base?
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_base", "--context", context, "--proj-tag", proj_tag, "--out-context", temp_root))
        base_image = build_cached_by_content(temp_root, image_repo, "c4push/.dockerconfigjson")
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_over", "--context", context, "--proj-tag", proj_tag, "--out-context", temp_root))
        crane_append(temp_root, base_image, rt_img)


def main():
    opt = setup_parser((
        ('ci_prep', lambda o: ci_prep(o.context, o.env_state, o.info_out), ("--context", "--env-state", "--info-out")),
        ('build_rt_inner', lambda o: build_rt_inner(o.context, o.proj_tag), ('--context', '--proj-tag')),
        #
        ('build_image'   , build_image   , ("--context","--repository","--push-secret-from-k8s","--name-out")),
        ('compile'       , compile       , ("--context","--image","--user","--proj-tag","--java-options")),
    )).parse_args()
    opt.op(opt)


main()

# argparse.Namespace
