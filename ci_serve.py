
import random
import json
import re
import sys
import os
import time
import tempfile
import subprocess

import c4util.snapshots as sn
import c4util.purge as pu
import c4util.cluster as cl
import c4util.git as git
from c4util import run, never, list_dir, log, Popen, wait_processes, run_text_out, read_json, one, read_text, never_if


def py_cmd(): return "python3", "-u"


def fix_kube_env(e): return {**e, "KUBECONFIG": e["C4KUBECONFIG"]}


def app_prep(app_dir, apps):
    out_dir_life = tempfile.TemporaryDirectory()
    for app in apps:
        up_path = f"{out_dir_life.name}/{app}.json"
        env = fix_kube_env(os.environ)
        cmd = (*py_cmd(), "/ci_prep.py", "--context", app_dir, "--c4env", app, "--state", "main", "--info-out", up_path)
        run(cmd, env=env)
    return out_dir_life


def app_start_purged(kube_context, app_dir, apps, get_app_snapshot):
    out_dir_life = app_prep(app_dir, apps)
    installs = [read_json(path) for path in list_dir(out_dir_life.name)]
    never_if([f'bad ctx {t["kube-context"]} of {t["c4env"]}' for t in installs if t["kube-context"] != kube_context])
    for it in installs:
        app_stop(kube_context, it["c4env"])
    install_prefixes = [(it, one(*sn.get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", [
        man["spec"]["template"] for man in it["manifests"] if man["kind"] == "Deployment"
    ]))) for it in installs]
    prefixes = {prefix for it, prefix in install_prefixes}
    kc = cl.get_kubectl(kube_context)
    while prefixes & pu.get_active_prefixes(kc):
        time.sleep(2)
    prefix_list = sorted(prefixes)
    pu.purge_prefix_list(kube_context, prefix_list)
    mc = sn.s3init(kc)
    for install, prefix in install_prefixes:
        sn.snapshot_put_purged(*get_app_snapshot(install["c4env"]), mc, prefix)
    app_up(out_dir_life.name)


def app_up(d):
    env = fix_kube_env(os.environ)
    up_proc_list = [Popen((*py_cmd(), "/ci_up.py"), stdin=open(path), env=env) for path in list_dir(d)]
    wait_processes(up_proc_list) or never("start failed")


def app_stop(kube_context, app):
    info = {"c4env": app, "state": "c4-off", "kube-context": kube_context, "manifests": []}
    run((*py_cmd(), "/ci_up.py"), text=True, input=json.dumps(info), env=fix_kube_env(os.environ))


def set_kube_contexts(kube_contexts): return (
    run_text_out(("kubectl", "config", "get-contexts", "-o", "name")).splitlines() if kube_contexts == "all" else
    kube_contexts
)


def remote_call(kube_context, act):
    arg = json.dumps([["call", act]])
    run((*cl.get_any_pod_exec(cl.get_kubectl(kube_context), "c4cio"), *py_cmd(), "/ci_serve.py", arg))


def select_def(def_list, s0, s1): return [d[2] for d in def_list if d[0] == s0 and d[1] == s1]


def load_no_die(path):
    s = read_text(path)
    try:
        return json.loads(s)
    except json.decoder.JSONDecodeError as e:
        log(f"error parsing {path}: {e}")


def load_def_list(life):
    paths = list_dir(f'{life.name}/{os.environ["C4CRON_DIR"]}')
    return [d for p in paths if p.endswith(".json") for c in [load_no_die(p)] if c for d in c]


def measure():
    log_path = f"/tmp/c4log-{random.random()}"
    log(f'starting task, to view log:\n\tkcd exec {os.environ["HOSTNAME"]} -- tail -f {log_path}')
    started = time.monotonic()
    with open(log_path, "w") as log_file:
        for line in sys.stdin:
            print(f"{str(int(time.monotonic()-started)).zfill(5)} {line}", end="", file=log_file)


def run_steps(ctx, sm_args, steps):
    handlers = get_step_handlers()
    patt = re.compile(r'\{(\w+)}|\{(\w+)\.name}')
    repl = (lambda a: (
        sm_args[a.group(1)] if a.group(1) and a.group(1) in sm_args else
        # ctx[a.group(2)].name if a.group(2) and a.group(2) in ctx else
        a.group(0)
    ))
    for step in steps:
        step_str = patt.sub(repl, json.dumps(step))
        log(step_str)
        op, *step_args = json.loads(step_str)
        ctx = handlers[op](ctx, *step_args)
    log("OK")
    return ctx


def git_access(kube_context, k8s_path): return cl.secret_part_to_text(cl.get_kubectl(kube_context), k8s_path)


def clone_def_repo():
    repo = git_access(os.environ["C4DEPLOY_CONTEXT"], os.environ["C4CRON_REPO"])
    return git.git_clone(repo, os.environ["C4CRON_BRANCH"])


def start(script, cmd, **options):
    pr = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **options)
    Popen((*py_cmd(), script, "measure"), stdin=pr.stdout)
    return pr


def main_operator(script):
    dir_life = clone_def_repo()
    last_tm_abbr = ""
    while True:
        tm = time.gmtime()
        tm_abbr = ("ETKNRLP"[tm.tm_wday], time.strftime("%H:%M", tm))
        if last_tm_abbr == tm_abbr:
            continue
        last_tm_abbr = tm_abbr
        run(("git", "pull"), cwd=dir_life.name)
        def_list = load_def_list(dir_life)
        log(f"at {tm_abbr}")
        acts = [
            *select_def(def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
            *select_def(def_list, "daily", tm_abbr[1])
        ]
        for act in acts:
            start(script, (*py_cmd(), script, json.dumps([["call", act]])))
        time.sleep(30)


def get_step_handlers(): return {
    "kube_contexts": lambda ctx, kube_contexts: {**ctx, "kube_contexts": set_kube_contexts(kube_contexts)},
    "snapshot_list_dump": lambda ctx, app: {**ctx, "": sn.snapshot_list_dump(ctx["kube_contexts"], app)},
    "snapshot_get": lambda ctx, app, name: {**ctx, "snapshot": sn.snapshot_get(ctx["kube_contexts"], app, name)},
    "snapshot_write": lambda ctx, dir_path: {**ctx, "": sn.snapshot_write(dir_path, *ctx["snapshot"])},
    "snapshot_read": lambda ctx, data_path_arg: {**ctx, "snapshot": sn.snapshot_read(data_path_arg)},
    "snapshot_put": lambda ctx, app: {**ctx, "": sn.snapshot_put(*ctx["snapshot"], ctx["kube_contexts"], app)},
    "snapshot_make": lambda ctx, app: {**ctx, "": sn.snapshot_make(ctx["kube_contexts"], app)},
    "snapshot_will": lambda ctx, app: {**ctx, f"snapshot-{app}": ctx["snapshot"]},
    "snapshot_put_purged": lambda ctx, prefix: {
        **ctx, "": sn.snapshot_put_purged(*ctx["snapshot"], sn.s3init(cl.get_kubectl(ctx["deploy_context"])), prefix)
    },
    "injection_suffix": lambda ctx, suffix: {**ctx, "injection_suffix": suffix},
    "injection_get": lambda ctx, name, subdir: {
        **ctx, "injection": sn.injection_get(f'{ctx[name].name}/{subdir}', ctx["injection_suffix"])
    },
    "injection_post": lambda ctx, app: {**ctx, "": sn.injection_post(ctx["injection"], ctx["kube_contexts"], app)},
    "injection_substitute": lambda ctx, fr, to: {**ctx, "injection": sn.injection_substitute(ctx["injection"], fr, to)},
    "injection_set": lambda ctx, value: {**ctx, "injection": value},
    "injection_dump": lambda ctx: {**ctx, "": log(f'injection:\n{ctx["injection"]}')},
    "git_repo": lambda ctx, name, k8s_path: {**ctx, f"repo-{name}": k8s_path},
    "git_clone": lambda ctx, name, branch: {
        **ctx, name: git.git_clone(git_access(ctx["deploy_context"], ctx[f"repo-{name}"]), branch)
    },
    "git_init": lambda ctx, name: {**ctx, name: git.git_init(git_access(ctx["deploy_context"], ctx[f"repo-{name}"]))},
    "git_add_tagged": lambda ctx, cwd, tag: {**ctx, "": git.git_add_tagged(ctx[cwd].name, tag)},
    "app_start_purged": lambda ctx, name, apps: {
        **ctx, "": app_start_purged(ctx["deploy_context"], ctx[name].name, apps, lambda app: ctx[f"snapshot-{app}"])
    },
    "app_stop": lambda ctx, kube_context, app: {**ctx, "": app_stop(kube_context, app)},
    "purge_mode_list": lambda ctx, mode_list: {**ctx, "": pu.purge_mode_list(ctx["deploy_context"], mode_list)},
    "purge_prefix_list": lambda ctx, prefix_list: {**ctx, "": pu.purge_prefix_list(ctx["deploy_context"], prefix_list)},
    "call": lambda ctx, msg: run_steps(ctx, msg, one(
        *select_def(load_def_list(clone_def_repo()), "def", msg["op"])
    )),
    "run": lambda ctx, cwd, cmd: {**ctx, "": run(cmd, cwd=ctx[cwd].name)},
    "remote_call": lambda ctx, msg: {**ctx, "": remote_call(ctx["deploy_context"], msg)},
    "start": lambda ctx, cwd, cmd: {
        **ctx, "proc": (*ctx.get("proc", []), start(ctx["script"], cmd, cwd=ctx[cwd].name))
    },
    "wait_all": lambda ctx: {**ctx, "all_ok": wait_processes(ctx.get("proc", []))},
    "rsync_files": lambda ctx, rsync_files: {**ctx, "rsync_files": "".join(f"{f}\n" for f in rsync_files)},
    "rsync_add": lambda ctx, fr, to: {
        **ctx, "": run(
            ("rsync", "-acr", "--files-from", "-", ctx[fr].name, ctx[to].name), text=True, input=ctx["rsync_files"]
        )
    },
}


def main(script, op): return (
    main_operator(script) if op == "main" else measure() if op == "measure" else
    run_steps({"script": script, "deploy_context": os.environ["C4DEPLOY_CONTEXT"]}, {}, json.loads(op))
)


main(*sys.argv)
