
import json
import os
import time
import typing
import uuid
import tempfile
import pathlib
import re
from c4util import path_exists, read_text, changing_text, read_json, one, \
    changing_text_observe
from c4util.build import never, run, run_no_die, run_pipe_no_die, Popen, \
    wait_processes, need_dir, kcd_args, kcd_run, need_pod, temp_dev_pod, \
    build_cached_by_content, setup_parser, secret_part_to_text, get_repo, crane_login, run_text_out, crane_image_exists


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
    commits = get_commits(opt.context)
    compile_options = get_more_compile_options(opt.context, commits[""], opt.proj_tag)
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
    push_secret = secret_part_to_text(opt.push_secret_from_k8s)
    image = build_cached_by_content(opt.context, opt.repository, push_secret)
    changing_text(opt.name_out, image)

def perl_exec(line):
    return "\n".join(('#!/usr/bin/perl','use strict;',line,'die;'))

def perl_env(k,v):
    return f'$ENV{{{k}}} ||= {json.dumps(v)};'


def get_commits(context):
    return dict(re.findall("(\\S*):(\\S*)", read_text(f"{context}/target/c4repo_commits")))


def get_plain_option(plain_conf, k):
    return one(*(line[2] for line in plain_conf if line[0] == k))


def ci_prep_gen(opt):
    proto_postfix = get_proto_postfix(opt.context)
    plain_conf = read_json(f"{opt.context}/c4dep.main.json")
    replink = get_plain_option(plain_conf, "C4REPLINK")
    deploy_context = get_plain_option(plain_conf, "C4DEPLOY_CONTEXT")
    changing_text(f"{opt.out}/c4prep", perl_exec("\n".join((
        'my $opt = sub{ my %opt = @ARGV; $opt{$_[0]} || die "no $_[0]" };',
        'my $dir = "/tmp/c4".rand();',
        'system "git", "clone", "--depth", "1", &$opt("--context"), $dir and die;',
        f'my $proto_dir = "$dir/{proto_postfix}";',
        f'system "C4REPO_MAIN_CONF=$dir/{replink} /replink.pl" and die;',
        f'system "C4REPO_MAIN_CONF=$proto_dir/c4dep.main.replink /replink.pl" and die;',
        perl_env("C4DEPLOY_CONTEXT", f'"{deploy_context}"'),
        perl_env("C4CI_PROTO_DIR", "$proto_dir"),
        'exec "python3","-u","$proto_dir/build_remote.py","ci_prep","--context",$dir,"--out",&$opt("--out");',
    ))))
    run(("chmod", "+x", f"{opt.out}/c4prep"))


def ci_prep(opt):
    proto_dir = get_proto_dir()
    plain_conf = read_json(f"{opt.context}/c4dep.main.json")
    image_prefix = get_plain_option(plain_conf, "C4COMMON_IMAGE_PREFIX")
    pathlib.Path(f"{opt.context}/target").mkdir()
    run(("perl", f"{proto_dir}/sync_mem.pl", opt.context))
    commits = get_commits(opt.context)  # after sync_mem
    image = f"{image_prefix}.{commits['']}"
    with tempfile.TemporaryDirectory() as temp_root:
        proto_postfix = get_proto_postfix(opt.context)
        deploy_context = get_plain_option(plain_conf, "C4DEPLOY_CONTEXT")
        build_dir = get_plain_option(plain_conf, "C4CI_BUILD_DIR")
        pathlib.Path(f"{temp_root}/base").mkdir()
        changing_text(f"{temp_root}/base/Dockerfile", "\n".join((
            read_text(f"{proto_dir}/build.def.dockerfile"),
            f"ENV C4DEPLOY_CONTEXT={deploy_context}",
            f"ENV C4CI_BUILD_DIR={build_dir}",
            f"ENV C4CI_PROTO_DIR={build_dir}/{proto_postfix}",
        )))
        push_secret = secret_part_to_text("c4push/.dockerconfigjson")
        base_image = build_cached_by_content(f"{temp_root}/base", get_repo(image_prefix), push_secret)
        if not crane_image_exists(image):
            run(("perl", f"{proto_dir}/sync_setup.pl"), env={**os.environ, "HOME": f"{temp_root}/common"})
            run(("rsync", "-a", "--exclude", ".git", f"{opt.context}/", need_dir(f"{temp_root}/common{build_dir}")))
            # shutil.copytree seems to be slower
            crane_append(f"{temp_root}/common", base_image, image)
            changing_text(
                need_dir(f"{temp_root}/de/c4")+"/c4serve.pl",
                perl_exec('exec "perl","$ENV{C4CI_PROTO_DIR}/sandbox.pl","main";')
            )
            crane_append(f"{temp_root}/de", image, f"{image}.de")
            tmp_bin = need_dir(f"{temp_root}/ce/c4/bin")
            aliases = (("c4ci", '"perl","$proto_dir/prod.pl"'),)
            for cmd, content in aliases:
                changing_text(f"{tmp_bin}/{cmd}", perl_exec("\n".join((
                    perl_env("C4COMMON_IMAGE", image),
                    'my $proto_dir = $ENV{C4CI_PROTO_DIR}||die "no C4CI_PROTO_DIR";', f'exec {content}, @ARGV;',
                ))))
            run(("chmod", "+x", *(f"{tmp_bin}/{cmd}" for cmd, content in aliases)))
            changing_text(need_dir(f"{temp_root}/ce/c4")+"/c4serve.pl", perl_exec('exec "sleep","infinity";'))
            crane_append(f"{temp_root}/ce", image, f"{image}.ce")
    with temp_dev_pod({"image": image, **opt_compiler()}) as name:
        out_path = "/tmp/.c4-out"
        kc_env = cp_kube_conf(name)
        cmd = (*self_cmd(f"{build_dir}/{proto_postfix}"), "ci_prep_inner", "--env", opt.env, "--out", out_path)
        kcd_run("exec", name, "--", *kc_env, *cmd)
        kcd_run("cp", f"{name}:{out_path}", opt.out)


def self_cmd(proto_dir):
    return "python3.8", "-u", f"{proto_dir}/build_remote.py"


def cp_kube_conf(name):
    remote_conf = "/tmp/.c4-kube-config"
    kcd_run("cp", os.environ["KUBECONFIG"], f"{name}:{remote_conf}")
    return "env", f"KUBECONFIG={remote_conf}"


def dev_pod_generator(image):
    with temp_dev_pod({"image": image, **opt_compiler()}) as name:
        yield name


def build_rt_inner(opt):
    context = os.environ["C4CI_BUILD_DIR"]
    image = os.environ["C4COMMON_IMAGE"]
    commit = get_commits(context)[""]
    proto_dir = get_proto_dir()
    prod = ("perl", f"{proto_dir}/prod.pl")
    pre = ("python3.8", "-u", f"{proto_dir}/run_with_prefix.py")
    run(("perl", f"{proto_dir}/build.pl"), cwd=context)
    proj_tag = opt.proj_tag
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
        push_secret = secret_part_to_text("c4push/.dockerconfigjson")
        base_image = build_cached_by_content(temp_root, get_repo(image), push_secret)
    with tempfile.TemporaryDirectory() as temp_root:
        run((*prod, "ci_rt_over", "--context", context, "--proj-tag", proj_tag, "--out-context", temp_root))
        crane_append(temp_root, base_image, rt_img)


def run_json_out(cmd):
    with tempfile.TemporaryDirectory() as temp_root:
        out_path = f"{temp_root}/out.json"
        run((*cmd, "--out", out_path))
        return json.loads(read_text(out_path))


def ci_prep_inner(opt):
    env_comp = opt.env
    proto_dir = get_proto_dir()
    deploy_info = run_json_out(("perl", f"{proto_dir}/prod.pl", "ci_deploy_info", "--names", json.dumps([env_comp])))
    parts = deploy_info["parts"]

    push_secret = secret_part_to_text("c4push/.dockerconfigjson")
    crane_login(push_secret)

    dev_pod_generators = [
        (dev_pod_generator(image), part["project"]) for part in parts if not crane_image_exists(part["from_image"])
    ]


    build_processes = [
        Popen(self_cmd(proto_dir, "build_rt", "--proj-tag", part["project"]))
        for part in parts if not crane_image_exists(part["from_image"])
    ]

    kc_env = cp_kube_conf(name)
    cmd = (*self_cmd(get_proto_dir()), "build_rt_inner", "--proj-tag", proj_tag)
    yield Popen(kcd_args("exec", name, "--", *kc_env, *cmd))

    env_url = next(sorted(f"https://{h}" for c in parts for h in [c["hostname"]] if h), "")


    # build_rt
    # push
    # gen up



def get_proto_postfix(context):
    proto_dir = get_proto_dir()
    proto_prefix, _, proto_postfix = proto_dir.rpartition("/")
    if proto_prefix != context: never(f"proto ({proto_dir}) out of context ({context})")
    return proto_postfix


def copy_image(opt):
    secret = secret_part_to_text(opt.secret_from_k8s)
    crane_login(secret)
    started = time.monotonic()
    while not crane_image_exists(opt.from_image):
        if time.monotonic() - started < 60*30:
            time.sleep(5)
        else:
            never("timeout")
    if opt.from_image == opt.to_image:
        return
    with temp_dev_pod({ "image": "quay.io/skopeo/stable:v1.10.0", **opt_sleep() }) as name:
        push_secret = secret_part_to_text(opt.push_secret_from_k8s)
        run(kcd_args("exec","-i",name,"--","sh","-c","cat > /tmp/auth.json"), text=True, input=push_secret)
        kcd_run("exec",name,"--","skopeo","copy",f"docker://{opt.from_image}",f"docker://{opt.to_image}")


def add_history(opt):
    if not path_exists(opt.context) or path_exists(f"{opt.context}/.git"):
        never("bad context")
    repo = secret_part_to_text("c4out-repo/value")
    with tempfile.TemporaryDirectory() as temp_root:
        if run_no_die(("git", "clone", "-b", opt.branch, "--depth", "1", "--", repo, "."), cwd=temp_root):
            run(("mv", f"{temp_root}/.git", opt.context))
        else:
            run(("git", "init"), cwd=opt.context)
            run(("git", "remote", "add", "origin", repo), cwd=opt.context)
            run(("git", "checkout", "-b", opt.branch), cwd=opt.context)
    run(("git", "add", "."), cwd=opt.context)
    run(("git", "config", "user.email", "ci@c4proto"), cwd=opt.context)
    run(("git", "config", "user.name", "ci@c4proto"), cwd=opt.context)
    if run_no_die(("git", "commit", "-m", opt.message), cwd=opt.context):
        run(("git", "push", "--set-upstream", "origin", opt.branch), cwd=opt.context)
    elif len(run_text_out(("git", "status", "--porcelain=v1"), cwd=opt.context).strip()) > 0:
        never("can not commit")
    else:
        print("unchanged")


def main():
    opt = setup_parser((
        ('ci_prep_gen'   , ci_prep_gen   , ("--context", "--out")),
        ('ci_prep'       , ci_prep       , ("--context", "--env", "--out")),
        ('ci_prep_inner' , ci_prep_inner , ("--context", "--env", "--out")),
        ('build_rt_inner', build_rt_inner, ('--proj-tag',)),
        #
        ('build_image'   , build_image   , ("--context","--repository","--push-secret-from-k8s","--name-out")),
        ('compile'       , compile       , ("--context","--image","--user","--proj-tag","--java-options")),
        ('copy_image'    , copy_image    , ("--from-image","--to-image","--secret-from-k8s","--push-secret-from-k8s")),
        ('add_history'   , add_history   , ("--context","--branch","--message")),
    )).parse_args()
    opt.op(opt)


main()

# argparse.Namespace
