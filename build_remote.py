
import json
import os
import subprocess
import sys
import time
import typing
import uuid
import tempfile
import re
from c4util import path_exists, read_text, changing_text, read_json, changing_text_observe, one
from c4util.build import never, run, run_no_die, run_pipe_no_die, Popen, \
    wait_processes, need_dir, kcd_args, kcd_run, need_pod, get_main_conf, get_temp_dev_pod, \
    build_cached_by_content, setup_parser, secret_part_to_text, crane_image_exists, get_proto, get_image_conf, \
    crane_login


def perl_exec(*lines):
    return "\n".join(('#!/usr/bin/perl', 'use strict;', *lines, 'die;'))


def c4dsync(kube_ctx):
    rsh_raw = f"/tmp/c4rsh_raw-{kube_ctx}"
    content = perl_exec(
        f'my ($pod,@args) = @ARGV;',
        f'exec "kubectl", "--context", "{kube_ctx}", "exec", "-i", $pod, "--", @args;'
    )
    for save in changing_text_observe(rsh_raw, content):
        save()
        run(("chmod", "+x", rsh_raw))
    return "rsync", "--blocking-io", "-e", rsh_raw


def rsync_args(kube_ctx, from_pod, to_pod): return (*c4dsync(kube_ctx), "-acr", "--del", "--files-from", "-", *(
    (f"{from_pod}:/", "/") if from_pod and not to_pod else
    ("/", f"{to_pod}:/") if not from_pod and to_pod else never("bad args")
))


def rsync(kube_ctx, from_pod, to_pod, files):
    run(rsync_args(kube_ctx, from_pod, to_pod), text=True, input="".join(f"{f}\n" for f in files))


def sbt_args(mod_dir, java_opt):
    return "env", "-C", mod_dir, f"JAVA_TOOL_OPTIONS={java_opt}", "sbt", "-Dsbt.color=true", "c4build"


def get_cb_name(v): return f"cb-v1-{v}"


class CompileOptions(typing.NamedTuple):
    mod: str
    mod_dir: str
    cache_pod_name: str
    cache_path: str
    java_options: str
    deploy_context: str


def get_more_compile_options(context, commit, proj_tag):
    build_conf = read_json(f"{context}/target/c4/build.json")
    mod = build_conf["tag_info"][proj_tag]["mod"]
    java_options = " ".join((line[2] for line in build_conf["plain"] if line[0] == "C4BUILD_JAVA_TOOL_OPTIONS"))
    mod_dir = f"{context}/target/c4/mod.{mod}.d"
    cache_pod_name = get_cb_name("cache")
    cache_path = f"/tmp/c4cache-{commit}-{proj_tag}"
    deploy_context, = {line[2] for line in build_conf["plain"] if line[0] == "C4DEPLOY_CONTEXT"}
    return CompileOptions(mod, mod_dir, cache_pod_name, cache_path, java_options, deploy_context)


def remote_compile(context, user, proj_tag):
    commit = get_commit(context)
    compile_options = get_more_compile_options(context, commit, proj_tag)
    mod_dir = compile_options.mod_dir
    cache_pod_name = compile_options.cache_pod_name
    cache_path = compile_options.cache_path
    pod = get_cb_name(f"u{user}")
    cp_path = f"{mod_dir}/target/c4classpath"
    need_pod(pod, lambda: {"image": need_base_image(context, get_main_conf(context)), **opt_compiler()})
    for save in changing_text_observe(f"{context}/target/c4/compile_cache_ver", cache_path):
        if not run_no_die(kcd_args("exec", pod, "--", "test", "-e", mod_dir)):
            print("private cache does not exist")
        elif not run_no_die(kcd_args("exec", pod, "--", "rm", "-r", mod_dir)):
            print("private cache rm failed")
            break
        if not run_no_die(kcd_args("exec", cache_pod_name, "--", "test", "-e", cache_path)):
            print("shared cache does not exist")
        else:
            kcd_run("exec", pod, "--", "mkdir", "-p", mod_dir)
            pipe_ok = run_pipe_no_die(
                kcd_args("exec", cache_pod_name, "--", "cat", cache_path),
                kcd_args("exec", "-i", pod, "--", "tar", "-C", mod_dir, "-xzf-")
            )
            if not pipe_ok:
                print("cache get failed")
                break
            print("cache get ok")
        save()
    full_sync_paths = (f"{context}/{part}" for part in json.loads(read_text(f"{mod_dir}/c4sync_paths.json")))
    rsync(compile_options.deploy_context, None, pod, [path for path in full_sync_paths if path_exists(path)])
    kcd_run("exec", pod, "--", *sbt_args(mod_dir, compile_options.java_options))
    rsync(compile_options.deploy_context, pod, None, [cp_path])
    rsync(compile_options.deploy_context, pod, None, read_text(cp_path).split(":"))


def push_compilation_cache(compile_options):
    mod_dir = compile_options.mod_dir
    cache_pod_name = compile_options.cache_pod_name
    cache_path = compile_options.cache_path
    cache_tmp_path = f"{cache_path}-{uuid.uuid4()}"
    pipe_ok = run_pipe_no_die(
        ("tar", "-C", mod_dir, "-czf-", "."),
        kcd_args("exec", "-i", cache_pod_name, "--", "sh", "-c", f"cat > {cache_tmp_path}")
    )
    if not pipe_ok:
        never("cache put failed")
    run(kcd_args("exec", cache_pod_name, "--", "mv", cache_tmp_path, cache_path))

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


def push_secret_name(): return "c4push/.dockerconfigjson"


def ci_prep(context, c4env, env_state, info_out):
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
    run(("perl", f"{proto_dir}/prod.pl", "ci_deploy_info", "--env-state", f"{c4env}-{env_state}", "--out", out_path))
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
    if c4env != one(*{man["metadata"]["labels"]["c4env"] for man in manifests}):
        never("bad c4env name")
    out = {"kube-context": kube_context, "manifests": manifests, "state": out_state, "c4env": c4env}
    changing_text(info_out, json.dumps(out, sort_keys=True, indent=4))
    #
    crane_login(secret_part_to_text(push_secret_name()), "")
    build_parts = [part for part in parts if not crane_image_exists(part["from_image"])]
    if build_parts:
        build_some_parts(build_parts, context, get_plain_option)
    #
    info("coping images to external registry ...")
    cp_parts = [part for part in parts if part["from_image"] != part["image"] and not crane_image_exists(part["image"])]
    if cp_parts:
        pod_life, name = get_temp_dev_pod({"image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep()})
        cat_secret_to_pod(name, "/tmp/auth.json", push_secret_name())
        for part in cp_parts:
            if part.get("only_source_repo"):
                never("source deploy to alien repo is not allowed")
            kcd_run("exec", name, "--", "skopeo", "copy", f"docker://"+part["from_image"], f"docker://"+part["image"])


def need_base_image(context, get_plain_option):
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    image_repo, image_tag_prefix = get_image_conf(get_plain_option)
    temp_root = tempfile.TemporaryDirectory()
    changing_text(f"{temp_root.name}/Dockerfile", read_text(f"{proto_dir}/build.def.dockerfile"))
    return build_cached_by_content(temp_root.name, image_repo, push_secret_name())


def build_some_parts(parts, context, get_plain_option):
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    info("making base builder image ...")
    base_image = need_base_image(context, get_plain_option)
    info("starting build tasks for images ...")
    cache_pod_nm = get_cb_name("cache")
    need_pod(cache_pod_nm, lambda: {"image": base_image, **opt_compiler()})  # todo: opt_cache_node
    build_pods = [(part, *get_temp_dev_pod({"image": base_image, **opt_compiler()})) for part in parts]
    remote_conf = "/tmp/.c4-kube-config"
    deploy_context = get_plain_option("C4DEPLOY_CONTEXT")
    for part, life, name in build_pods:
        run((*c4dsync(deploy_context), "-ac", "--del", "--exclude", ".git", f"{context}/", f"{name}:{context}/"))
        # syncing just '/' does not work, because can not change time for '/tmp'
        cat_secret_to_pod(name, remote_conf, get_plain_option("C4DEPLOY_CONFIG"))
    processes = [
        (
            part_name, build_proc, " ".join(kcd_args("exec", cache_pod_nm, "--", "cat", log_path)),
            Popen(kcd_args("exec", "-i", cache_pod_nm, "--", "sh", "-c", f"cat > {log_path}"), stdin=build_proc.stdout)
        )
        for part, life, name in build_pods
        for part_name, log_path in [(part['name'], f"/tmp/c4log-{name}")]
        for build_proc in [Popen(kcd_args(
            "exec", name, "--", "env", f"KUBECONFIG={remote_conf}", f"C4DEPLOY_CONTEXT={deploy_context}",
            "python3", "-u", f"{proto_dir}/build_remote.py",
            "build_inner", "--context", context, "--proj-tag", part["project"], "--image-type", part["image_type"]
        ), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)]
    ]
    print("To view logs:\n"+"\n".join(
        f" ={part_name}=\n  {log_cmd.replace(' cat ',' tail -f ')}" for part_name, b_proc, log_cmd, l_proc in processes
    ))
    info("waiting images ...")
    started = time.monotonic()
    for part in parts:
        while not crane_image_exists(part["from_image"]):
            for part_name, build_proc, log_cmd, log_proc in processes:
                if build_proc.poll() or log_proc.poll():
                    print(f"{part_name} failed, to view logs:\n {log_cmd}")
                    never("build failed")
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
    pre_image = build_cached_by_content(temp_root_pre.name, image_repo, push_secret_name())
    crane_append(out_context.name, pre_image, res_image)


def build_type_elector(context, out):
    build_micro(context, out, ["elector.js"], [
        "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
        "RUN perl install.pl useradd 1979",
        "RUN perl install.pl apt curl ca-certificates xz-utils",  # xz-utils for node
        "RUN perl install.pl curl https://nodejs.org/dist/v20.5.0/node-v20.5.0-linux-x64.tar.xz",
        "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini" +
        " && chmod +x /tools/tini",
        "USER c4",
        'ENTRYPOINT ["/tools/tini","--","/tools/node/bin/node","/elector.js"]',
    ])


def build_type_resource_tracker(context, out):
    build_micro(context, out, ["resources.py"], [
        "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
        "RUN perl install.pl useradd 1979",
        "RUN perl install.pl apt curl ca-certificates python3",
        "RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl",
        "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini" +
        " && chmod +x /tools/tini",
        "USER c4",
        'ENV PATH=${PATH}:/tools',
        'ENTRYPOINT ["/tools/tini","--","python3","/resources.py","tracker"]',
    ])


def build_type_s3client(context, out):
    build_micro(context, out, [], [
        "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
        "RUN perl install.pl useradd 1979",
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
    run(("perl", f"{proto_dir}/sync_setup.pl", need_dir(f"{out}/tools")))
    run(("rsync", "-a", "--exclude", ".git", f"{context}/", need_dir(f"{out}{build_dir}")))
    # shutil.copytree seems to be slower
    need_dir(f"{out}/c4")
    changing_text(f"{out}/c4/c4serve.pl", perl_exec('exec "perl","$ENV{C4CI_PROTO_DIR}/sandbox.pl","main";'))
    changing_text(f"{out}/c4/debug-tag", proj_tag)


def build_type_rt(proj_tag, context, out):
    get_plain_option = get_main_conf(context)
    proto_postfix, proto_dir = get_proto(context, get_plain_option)
    pr_env = {"C4CI_PROTO_DIR": proto_dir, "PATH": os.environ["PATH"]}
    prod = ("perl", f"{proto_dir}/prod.pl")
    pre = ("python3", "-u", f"{proto_dir}/run_with_prefix.py")
    client_proc_opt = (
        [Popen((*pre, "=client=", *prod, "build_client", context), env=pr_env)] if proj_tag != "def" else ()
    )
    run(("python3", f"{proto_dir}/build.py", context))
    compile_options = get_more_compile_options(context, get_commit(context), proj_tag)  # after build.py
    mod = compile_options.mod
    mod_dir = compile_options.mod_dir
    run((*pre, f"=sbt=", *sbt_args(mod_dir, compile_options.java_options)))
    check_proc = Popen((*pre, "=check=", *prod, "ci_rt_chk", context, mod), env=pr_env)
    push_compilation_cache(compile_options)
    wait_processes(client_proc_opt) or never("client build failed")  # before ci_rt_base?
    wait_processes((check_proc,)) or never("check failed")  # before ci_rt_base?
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
        (
            'ci_prep',
            lambda o: ci_prep(o.context, o.c4env, o.state, o.info_out),
            ("--context", "--c4env", "--state", "--info-out")
        ),
        (
            'build_inner',
            lambda o: build_inner(handlers, o.context, o.image_type, o.proj_tag),
            ('--context', "--image-type", '--proj-tag')
        ),
        #
        ('compile', lambda o: remote_compile(o.context, o.user, o.proj_tag), ("--context", "--user", "--proj-tag")),
    )).parse_args()
    opt.op(opt)


main()

# argparse.Namespace
# "docker/config.json"
