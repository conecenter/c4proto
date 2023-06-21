
import json
import os
import sys
import time
import typing
import uuid
import tempfile
import re
from c4util import group_map, path_exists, read_text, changing_text, read_json, changing_text_observe
from c4util.build import never, run, run_no_die, run_pipe_no_die, Popen, \
    wait_processes, need_dir, kcd_args, kcd_run, need_pod, get_main_conf, get_temp_dev_pod, \
    build_cached_by_content, setup_parser, secret_part_to_text, crane_image_exists


def rsync_args(from_pod, to_pod): return ("c4dsync", "-acr", "--del", "--files-from", "-", *(
    (f"{from_pod}:/", "/") if from_pod and not to_pod else
    ("/", f"{to_pod}:/") if not from_pod and to_pod else never("bad args")
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


def push_compilation_cache(compile_options):
    mod_dir = compile_options.mod_dir
    cache_pod_name = compile_options.cache_pod_name
    cache_path = compile_options.cache_path
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


def info(text):
    print(f"[[{time.monotonic()}]] {text}", file=sys.stderr)


def get_common_image_name(get_plain_option, commit):
    image_repo = get_plain_option("C4CI_IMAGE_REPO")
    image_tag_prefix = get_plain_option("C4CI_IMAGE_TAG_PREFIX")
    return image_repo, f"{image_tag_prefix}.{commit}",


def cat_secret_to_pod(name, path, secret_nm):
    run(kcd_args("exec", "-i", name, "--", "sh", "-c", f"cat >{path}"), text=True, input=secret_part_to_text(secret_nm))


def ci_prep(context, env_state, info_out):
    temp_root = tempfile.TemporaryDirectory()
    info("reading general build settings ...")
    get_plain_option = get_main_conf(context)
    proto_postfix = get_plain_option("C4PROTO_POSTFIX")
    proto_dir = f"{context}/{proto_postfix}"
    info("getting commit info ...")
    need_dir(f"{context}/target")
    run(("perl", f"{proto_dir}/sync_mem.pl", context))
    commit = get_commits(context)['']  # after sync_mem
    image_repo, image_tag_prefix = get_common_image_name(get_plain_option, commit)
    #
    info("making deploy info ...")
    out_path = f"{temp_root.name}/out"
    run(("perl", f"{proto_dir}/prod.pl", "ci_deploy_info", "--env-state", env_state, "--out", out_path))
    deploy_info = read_json(out_path)
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
    run(("perl", f"{proto_dir}/make_manifests.pl", "--values", json.dumps(parts), "--out", out_path))
    manifests = [json.loads(t) for t in read_text(out_path).split("\n")]
    env_url = max([""]+[f"https://{h}" for c in parts for h in [c.get("ci:hostname")] if h])
    out = {"manifests": manifests, "url": env_url, **{k: deploy_info[k] for k in ("name", "group", "context")}}
    changing_text(info_out, json.dumps(out, sort_keys=True, indent=4))
    #
    build_some_parts(parts, get_plain_option, context, proto_postfix, image_repo)
    #
    info("coping images to external registry ...")
    copy_parts = [part for part in parts if (
        False if part["from_image"] == part["image"] else
        never("source deploy to alien repo is not allowed") if part["image_type"] != "rt" else
        not crane_image_exists(part["image"])
    )]
    if copy_parts:
        pod_life, name = get_temp_dev_pod({"image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep()})
        cat_secret_to_pod(name, "/tmp/auth.json", "c4push/.dockerconfigjson")
        for part in copy_parts:
            kcd_run("exec", name, "--", "skopeo", "copy", f"docker://"+part["from_image"], f"docker://"+part["image"])


def build_some_parts(parts, get_plain_option, context, proto_postfix, image_repo):
    started = time.monotonic()
    proto_dir = f"{context}/{proto_postfix}"
    info("getting image build tasks ...")
    build_parts = [part for part in parts if not crane_image_exists(part["from_image"])]
    if not build_parts:
        return
    build_parts_by_type = group_map(build_parts, lambda p: (p["image_type"], p))
    temp_root_life = tempfile.TemporaryDirectory()
    temp_root = temp_root_life.name
    push_secret_name = "c4push/.dockerconfigjson"
    #
    info("making base builder image ...")
    changing_text(need_dir(f"{temp_root}/base")+"/Dockerfile", read_text(f"{proto_dir}/build.def.dockerfile"))
    base_image = build_cached_by_content(f"{temp_root}/base", image_repo, push_secret_name)
    #
    info("building dev images ...")
    for part in build_parts_by_type.get("de", []):
        build_dir = get_plain_option("C4CI_BUILD_DIR")
        deploy_context = get_plain_option("C4DEPLOY_CONTEXT")
        changing_text(need_dir(f"{temp_root}/pre")+"/Dockerfile", "\n".join((
            f"FROM {base_image}",
            f"ENV C4DEPLOY_CONTEXT={deploy_context}",
            f"ENV C4CI_BUILD_DIR={build_dir}",
            f"ENV C4CI_PROTO_DIR={build_dir}/{proto_postfix}",
        )))
        pre_image = build_cached_by_content(f"{temp_root}/pre", image_repo, push_secret_name)
        run(("perl", f"{proto_dir}/sync_setup.pl"), env={**os.environ, "HOME": f"{temp_root}/de"})
        run(("rsync", "-a", "--exclude", ".git", f"{context}/", need_dir(f"{temp_root}/de{build_dir}")))
        # shutil.copytree seems to be slower
        changing_text(need_dir(f"{temp_root}/de/c4")+"/c4serve.pl", "\n".join(
            ('#!/usr/bin/perl', 'use strict;', 'exec "perl","$ENV{C4CI_PROTO_DIR}/sandbox.pl","main";', 'die;')
        ))
        crane_append(f"{temp_root}/de", pre_image, part["from_image"])
    #
    info("starting remote build tasks for rt images ...")
    build_rt_parts = build_parts_by_type.get("rt", [])
    if build_rt_parts:
        need_pod(get_cb_name("cache"), lambda: {"image": base_image, **opt_compiler()})  # todo: opt_cache_node
    build_rt_pods = [build_rt(part, get_plain_option, base_image, context, proto_dir) for part in build_rt_parts]
    #
    info("waiting images ...")
    for part in parts:
        while not crane_image_exists(part["from_image"]):
            for pod_life, check in build_rt_pods:
                check()
            if time.monotonic() - started > 60*30:
                never("timeout")
            time.sleep(5)


def build_rt(part, get_plain_option, base_image, context, proto_dir):
    pod_life, name = get_temp_dev_pod({"image": base_image, **opt_compiler()})
    run(("c4dsync", "-ac", "--del", "--exclude", ".git", f"{context}/", f"{name}:{context}/"))
    # syncing just '/' does not work, because can not change time for '/tmp'
    remote_conf = "/tmp/.c4-kube-config"
    deploy_context = get_plain_option("C4DEPLOY_CONTEXT")
    cat_secret_to_pod(name, remote_conf, get_plain_option("C4DEPLOY_CONFIG"))
    proc = Popen(kcd_args(
        "exec", name, "--",
        "env", f"KUBECONFIG={remote_conf}", f"C4DEPLOY_CONTEXT={deploy_context}",
        "python3.8", "-u", f"{proto_dir}/run_with_prefix.py", f"={part['name']}=",
        "python3.8", "-u", f"{proto_dir}/build_remote.py",
        "build_rt_inner", "--context", context, "--proj-tag", part["project"]
    ))
    return pod_life, lambda: proc.poll() and never(part["name"])


def build_rt_inner(context, proj_tag):
    get_plain_option = get_main_conf(context)
    proto_postfix = get_plain_option("C4PROTO_POSTFIX")
    proto_dir = f"{context}/{proto_postfix}"
    commit = get_commits(context)[""]
    image_repo, image_tag_prefix = get_common_image_name(get_plain_option, commit)
    prod = ("perl", f"{proto_dir}/prod.pl")
    pre = ("python3.8", "-u", f"{proto_dir}/run_with_prefix.py")
    run(("perl", f"{proto_dir}/build.pl"), cwd=context, env={
        "C4CI_PROTO_DIR": proto_dir,
        "PATH": os.environ["PATH"],  # sbt
        "HOME": os.environ["HOME"],  # c4client_prep
    })
    client_proc_opt = (Popen((*pre, "=client=", *prod, "build_client_changed", context)),) if proj_tag != "def" else ()  # after build.pl
    rt_img = f"{image_repo}:{image_tag_prefix}.{proj_tag}.rt"
    compile_options = get_more_compile_options(context, commit, proj_tag)  # after build.pl
    mod = compile_options.mod
    mod_dir = compile_options.mod_dir
    run((*pre, f"={proj_tag}=", *sbt_args(mod_dir, compile_options.java_options)))
    run(("perl", f"{proto_dir}/build_env.pl", context, mod))
    check_proc = Popen((*pre, "=check=", *prod, "ci_rt_chk", context, mod), env={
        "C4CI_PROTO_DIR": proto_dir, "PATH": os.environ["PATH"],  # python
    })  # after build_env.pl
    push_compilation_cache(compile_options)
    wait_processes((check_proc, *client_proc_opt)) or never("build failed")  # before ci_rt_base?
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_base", "--context", context, "--proj-tag", proj_tag, "--out-context", temp_root), env={
            "C4CI_PROTO_DIR": proto_dir, "PATH": os.environ["PATH"],
        })
        base_image = build_cached_by_content(temp_root, image_repo, "c4push/.dockerconfigjson")
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_over", "--context", context, "--proj-tag", proj_tag, "--out-context", temp_root), env={
            "C4CI_PROTO_DIR": proto_dir, "PATH": os.environ["PATH"],
        })
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
