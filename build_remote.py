
import json
import os
import subprocess
import sys
import time
import typing
import uuid
import tempfile
import re
from c4util import path_exists, read_text, changing_text, read_json, changing_text_observe
from c4util.build import never, run, run_no_die, run_pipe_no_die, Popen, \
    wait_processes, need_dir, kcd_args, kcd_run, need_pod, get_main_conf, get_temp_dev_pod, \
    build_cached_by_content, setup_parser, secret_part_to_text, crane_image_exists, get_proto, get_image_conf


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
    commit = get_commit(opt.context)
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


def get_commits(context):
    return dict(re.findall("(\\S*):(\\S*)", read_text(f"{context}/target/c4repo_commits")))


def get_commit(context):
    return get_commits(context)[""]


def info(text):
    print(f"[[{time.monotonic()}]] {text}", file=sys.stderr)


def cat_secret_to_pod(name, path, secret_nm):
    run(kcd_args("exec", "-i", name, "--", "sh", "-c", f"cat >{path}"), text=True, input=secret_part_to_text(secret_nm))


def ci_prep(context, env_state, info_out):
    temp_root = tempfile.TemporaryDirectory()
    info("reading general build settings ...")
    get_plain_option = get_main_conf(context)
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    image_repo, image_tag_prefix = get_image_conf(get_plain_option)
    info("getting commit info ...")
    need_dir(f"{context}/target")
    run(("perl", f"{proto_dir}/sync_mem.pl", context))
    commit = get_commit(context)  # after sync_mem
    #
    info("making deploy info ...")
    out_path = f"{temp_root.name}/out"
    run(("perl", f"{proto_dir}/prod.pl", "ci_deploy_info", "--env-state", env_state, "--out", out_path))
    parts = [
        {
            **part, "from_image": f"{image_repo}:{image_tag_prefix}.{commit}.{part['project']}.{part['image_type']}",
            "image":
                f"{part['to_repo'] or image_repo}:{image_tag_prefix}.{commit}.{part['project']}.{part['image_type']}",
        } for part in read_json(out_path)
    ]
    run(("perl", f"{proto_dir}/make_manifests.pl", "--values", json.dumps(parts), "--out", out_path))
    manifests = read_json(out_path)
    out_state = f"{image_tag_prefix}.{commit}.{env_state}"
    kube_context, = {part["context"] for part in parts}
    out = {"context": kube_context, "manifests": manifests, "state": out_state}
    changing_text(info_out, json.dumps(out, sort_keys=True, indent=4))
    #
    build_parts = [part for part in parts if not crane_image_exists(part["from_image"])]
    if build_parts:
        build_some_parts(build_parts, get_plain_option, context, proto_postfix, image_repo)
    #
    info("coping images to external registry ...")
    cp_parts = [part for part in parts if part["from_image"] != part["image"] and not crane_image_exists(part["image"])]
    allowed = {"rt", "sy"}
    if cp_parts:
        pod_life, name = get_temp_dev_pod({"image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep()})
        cat_secret_to_pod(name, "/tmp/auth.json", "c4push/.dockerconfigjson")
        for part in cp_parts:
            if part["image_type"] not in allowed:
                never("source deploy to alien repo is not allowed")
            kcd_run("exec", name, "--", "skopeo", "copy", f"docker://"+part["from_image"], f"docker://"+part["image"])


def build_some_parts(parts, get_plain_option, context, proto_postfix, image_repo):
    proto_dir = f"{context}/{proto_postfix}"
    info("making base builder image ...")
    temp_root = tempfile.TemporaryDirectory()
    changing_text(f"{temp_root.name}/Dockerfile", read_text(f"{proto_dir}/build.def.dockerfile"))
    base_image = build_cached_by_content(temp_root.name, image_repo, "c4push/.dockerconfigjson")
    info("starting build tasks for images ...")
    cache_pod_nm = get_cb_name("cache")
    need_pod(cache_pod_nm, lambda: {"image": base_image, **opt_compiler()})  # todo: opt_cache_node
    build_pods = [(part, *get_temp_dev_pod({"image": base_image, **opt_compiler()})) for part in parts]
    remote_conf = "/tmp/.c4-kube-config"
    deploy_context = get_plain_option("C4DEPLOY_CONTEXT")
    for part, life, name in build_pods:
        run(("c4dsync", "-ac", "--del", "--exclude", ".git", f"{context}/", f"{name}:{context}/"))
        # syncing just '/' does not work, because can not change time for '/tmp'
        cat_secret_to_pod(name, remote_conf, get_plain_option("C4DEPLOY_CONFIG"))
    processes = [
        (
            part_name, build_proc, " ".join(kcd_args("exec", cache_pod_nm, "--", "tail", "-f", log_path)),
            Popen(kcd_args("exec", "-i", cache_pod_nm, "--", "sh", "-c", f"cat > {log_path}"), stdin=build_proc.stdout)
        )
        for part, life, name in build_pods
        for part_name, log_path in [(part['name'], f"/tmp/c4log-{name}")]
        for build_proc in [Popen(kcd_args(
            "exec", name, "--", "env", f"KUBECONFIG={remote_conf}", f"C4DEPLOY_CONTEXT={deploy_context}",
            "python3.8", "-u", f"{proto_dir}/build_remote.py",
            "build_inner", "--context", context, "--proj-tag", part["project"], "--image-type", part["image_type"]
        ), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)]
    ]
    print("To view logs:\n"+"\n".join(f"  ={part_name}= {log_cmd}" for part_name, b_proc, log_cmd, l_proc in processes))
    info("waiting images ...")
    started = time.monotonic()
    for part in parts:
        while not crane_image_exists(part["from_image"]):
            for part_name, build_proc, log_cmd, log_proc in processes:
                if build_proc.poll() or log_proc.poll():
                    never(part_name)
            if time.monotonic() - started > 60*30:
                never("timeout")
            time.sleep(5)


def build_inner(handlers, context, image_type, proj_tag):
    get_plain_option = get_main_conf(context)
    image_repo, image_tag_prefix = get_image_conf(get_plain_option)
    commit = get_commit(context)
    res_image = f"{image_repo}:{image_tag_prefix}.{commit}.{proj_tag}.{image_type}"
    out_context = tempfile.TemporaryDirectory()
    handlers[f"build_type-{image_type}"](proj_tag)(context, out_context.name)
    temp_root_pre = tempfile.TemporaryDirectory()
    run(("mv", f"{out_context.name}/Dockerfile", temp_root_pre.name))
    pre_image = build_cached_by_content(temp_root_pre.name, image_repo, "c4push/.dockerconfigjson")
    crane_append(out_context.name, pre_image, res_image)


def build_type_elector(context, out):
    build_micro(context, out, ["elector.js"], [
        "FROM ubuntu:20.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
        "RUN perl install.pl useradd 1979",
        "RUN perl install.pl apt curl ca-certificates xz-utils",  # xz-utils for node
        "RUN perl install.pl curl https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz",
        "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini" +
        " && chmod +x /tools/tini",
        "USER c4",
        'ENTRYPOINT ["/tools/tini","--","/tools/node/bin/node","/elector.js"]',
    ])


def build_type_resource_tracker(context, out):
    build_micro(context, out, ["resources.py"], [
        "FROM ubuntu:20.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
        "RUN perl install.pl useradd 1979",
        "RUN perl install.pl apt curl ca-certificates python3.8",
        "RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl",
        "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini" +
        " && chmod +x /tools/tini",
        "USER c4",
        'ENV PATH=${PATH}:/tools',
        'ENTRYPOINT ["/tools/tini","--","python3.8","/resources.py","tracker"]',
    ])


def build_type_s3client(context, out):
    build_micro(context, out, [], [
        "ARG C4UID=1979",
        "FROM ghcr.io/conecenter/c4replink:v2",
        "USER root",
        "RUN perl install.pl apt curl ca-certificates",
        "RUN /install.pl curl https://dl.min.io/client/mc/release/linux-amd64/mc && chmod +x /tools/mc",
        'ENTRYPOINT /tools/mc alias set def' +
        ' $(cat $C4S3_CONF_DIR/address) $(cat $C4S3_CONF_DIR/key) $(cat $C4S3_CONF_DIR/secret) && exec sleep infinity '
    ])


def build_micro(context, out, scripts, lines):
    get_plain_option = get_main_conf(context)
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    changing_text(f"{out}/Dockerfile", "\n".join(lines))
    for script in scripts:
        changing_text(f"{out}/{script}", read_text(f"{proto_dir}/{script}"))


def build_type_de(proj_tag, context, out):
    get_plain_option = get_main_conf(context)
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    build_dir = get_plain_option("C4CI_BUILD_DIR")
    deploy_context = get_plain_option("C4DEPLOY_CONTEXT")
    changing_text(f"{out}/Dockerfile", "\n".join((
        read_text(f"{proto_dir}/build.def.dockerfile"),
        f"ENV C4DEPLOY_CONTEXT={deploy_context}",
        f"ENV C4CI_BUILD_DIR={build_dir}",
        f"ENV C4CI_PROTO_DIR={build_dir}/{proto_postfix}",
    )))
    run(("perl", f"{proto_dir}/sync_setup.pl"), env={**os.environ, "HOME": out})
    run(("rsync", "-a", "--exclude", ".git", f"{context}/", need_dir(f"{out}{build_dir}")))
    # shutil.copytree seems to be slower
    changing_text(need_dir(f"{out}/c4")+"/c4serve.pl", "\n".join(
        ('#!/usr/bin/perl', 'use strict;', 'exec "perl","$ENV{C4CI_PROTO_DIR}/sandbox.pl","main";', 'die;')
    ))
    changing_text(f"{out}/c4/debug-tag", proj_tag)


def build_type_rt(proj_tag, context, out):
    get_plain_option = get_main_conf(context)
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    prod = ("perl", f"{proto_dir}/prod.pl")
    pre = ("python3.8", "-u", f"{proto_dir}/run_with_prefix.py")
    run(("perl", f"{proto_dir}/build.pl"), cwd=context, env={
        "C4CI_PROTO_DIR": proto_dir,
        "PATH": os.environ["PATH"],  # sbt
        "HOME": os.environ["HOME"],  # c4client_prep
    })
    client_proc_opt = (
        (Popen((*pre, "=client=", *prod, "build_client_changed", context)),) if proj_tag != "def" else
        ()  # after build.pl
    )
    compile_options = get_more_compile_options(context, get_commit(context), proj_tag)  # after build.pl
    mod = compile_options.mod
    mod_dir = compile_options.mod_dir
    run((*pre, f"=sbt=", *sbt_args(mod_dir, compile_options.java_options)))
    run(("perl", f"{proto_dir}/build_env.pl", context, mod))
    pr_env = {"C4CI_PROTO_DIR": proto_dir, "PATH": os.environ["PATH"]}
    check_proc = Popen((*pre, "=check=", *prod, "ci_rt_chk", context, mod), env=pr_env)  # after build_env.pl
    push_compilation_cache(compile_options)
    wait_processes((check_proc, *client_proc_opt)) or never("build failed")  # before ci_rt_base?
    run((*prod, "ci_rt_base", "--context", context, "--proj-tag", proj_tag, "--out-context", out), env=pr_env)
    run((*prod, "ci_rt_over", "--context", context, "--proj-tag", proj_tag, "--out-context", out), env=pr_env)


def main():
    handlers = {
        "build_type-rt": lambda proj_tag: (lambda *args: build_type_rt(proj_tag, *args)),
        "build_type-de": lambda proj_tag: (lambda *args: build_type_de(proj_tag, *args)),
        "build_type-elector": lambda proj_tag: build_type_elector,
        "build_type-resource_tracker": lambda proj_tag: build_type_resource_tracker,
        "build_type-s3client": lambda proj_tag: build_type_s3client,
    }
    opt = setup_parser((
        ('ci_prep', lambda o: ci_prep(o.context, o.env_state, o.info_out), ("--context", "--env-state", "--info-out")),
        (
            'build_inner',
            lambda o: build_inner(handlers, o.context, o.image_type, o.proj_tag),
            ('--context', "--image-type", '--proj-tag')
        ),
        #
        ('compile', compile, ("--context", "--image", "--user", "--proj-tag", "--java-options")),
    )).parse_args()
    opt.op(opt)


main()

# argparse.Namespace
# "docker/config.json"
