
from argparse import ArgumentParser
from json import dumps, loads
from pathlib import Path
from subprocess import check_output, Popen, PIPE, run

from c4util import group_map, da, changing_observe, die, perl_exec, parse_classpath, get_more_compile_options

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

def apply_manifest(kc, manifest):
    check_output(da(*kc,"apply","-f-"), input=dumps(manifest, sort_keys=True).encode())

def wait_pod(kc, pod,timeout,phases):
    phase_lines = { f"{phase}\n": phase for phase in phases }
    args = (*kc,"get","pod",pod,"--watch","-o",'jsonpath={.status.phase}{"\\n"}',"--request-timeout",f"{timeout}s")
    with Popen(da(args), stdout=PIPE, text=True) as proc:
        for line in proc.stdout:
            print(line)
            if line in phase_lines:
                proc.kill()
                return phase_lines[line]
    raise Exception("pod waiting failed")

# need_pod: image/versions/attrs need to be encoded in name
def need_pod(kube_context, name, get_opt):
    kc = ("kubectl", "--context", kube_context)
    if run(da(*kc,"get","pod",name)).returncode != 0: apply_manifest(kc, construct_pod({ **get_opt(), "name": name }))
    wait_pod(kc, name,60,("Running",))

def c4dsync(kube_ctx):
    rsh_raw = f"/tmp/c4rsh_raw-{kube_ctx}"
    content = perl_exec(
        f'my ($pod,@args) = @ARGV;',
        f'exec "kubectl", "--context", "{kube_ctx}", "exec", "-i", $pod, "--", @args;'
    )
    for save in changing_observe(Path(rsh_raw), content.encode()):
        save()
        check_output(da("chmod", "+x", rsh_raw))
    return "rsync", "--blocking-io", "-e", rsh_raw

def rsync_args(kube_ctx, from_pod, to_pod): return (*c4dsync(kube_ctx), "-acr", "--del", "--files-from", "-", *(
    (f"{from_pod}:/", "/") if from_pod and not to_pod else
    ("/", f"{to_pod}:/") if not from_pod and to_pod else die(Exception("bad args"))
))

def rsync(kube_ctx, from_pod, to_pod, files):
    check_output(da(*rsync_args(kube_ctx, from_pod, to_pod)), text=True, input="".join(f"{f}\n" for f in files))

def get_cb_name(v): return f"cb-v1-{v}"

def need_base_image(kube_context, context):
    build = ("python3", "-u", str(Path(__file__).parent/"ci_build.py"))
    args = ("--kube-context", kube_context, "--context", f'{context}/Dockerfile', "--opt", dumps({"image_type": "base"}))
    return check_output((*build, *args)).decode().strip()

def remote_compile(kube_context, context, user, proj_tag):
    tag_info, mod_dir, sbt_args = get_more_compile_options(context, proj_tag)
    pod = get_cb_name(f"u{user}")
    cp_path = f"{mod_dir}/target/c4classpath"
    need_pod(kube_context, pod, lambda: {"image": need_base_image(kube_context, context), **opt_compiler()})
    full_sync_paths = (f"{context}/{part}" for part in loads(Path(f"{mod_dir}/c4sync_paths.json").read_bytes()))
    rsync(kube_context, None, pod, [path for path in full_sync_paths if Path(path).exists()])
    check_output(da("kubectl", "--context", kube_context, "exec", pod, "--", *sbt_args))
    rsync(kube_context, pod, None, [cp_path])
    rsync(kube_context, pod, None, parse_classpath(Path(cp_path).read_bytes().decode()))

def opt_sleep(): return { "command": ["sleep", "infinity"] }
def opt_pull_secret(): return { "imagePullSecrets": [{ "name": "c4pull" }] }
def opt_cpu_node(): return {
    "nodeSelector": { "c4builder": "true" },
    "tolerations": [{ "key": "c4builder", "operator": "Exists", "effect": "NoSchedule" }],
}
def opt_compiler(): return { **opt_pull_secret(), **opt_sleep(), **opt_cpu_node() }

def main():
    parser = ArgumentParser()
    parser.add_argument("--kube-context", required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--proj-tag", required=True)
    remote_compile(**vars(parser.parse_args()))

main()