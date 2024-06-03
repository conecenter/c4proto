
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
from c4util import run, never, list_dir, log, Popen, wait_processes, run_text_out, read_json, one


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


def app_start_purged(kube_context, app_dir, apps, data_fn, data):
    out_dir_life = app_prep(app_dir, apps)
    installs = [read_json(path) for path in list_dir(out_dir_life.name)]
    for it in installs:
        if it["kube-context"] != kube_context:
            never(f'bad context: {it["kube-context"]} of {it["c4env"]}')
    for it in installs:
        app_stop(kube_context, it["c4env"])
    templates = [man["spec"]["template"] for it in installs for man in it["manifests"] if man["kind"] == "Deployment"]
    prefixes = sn.get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", templates)
    while prefixes & pu.get_active_prefixes(cl.get_kubectl(kube_context)):
        time.sleep(2)
    prefix_list = sorted(prefixes)
    pu.purge_prefix_list(kube_context, prefix_list)
    sn.snapshot_put_purged(data_fn, data, kube_context, prefix_list)
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


def op_dump(ctx):
    data = ctx.get("injection")
    if data:
        log(f"injection:\n{data}")
    return ctx


def load_def_list(life, s0, s1):
    paths = list_dir(f'{life.name}/{os.environ["C4CRON_DIR"]}')
    return [d[2] for p in paths if p.endswith(".json") for d in read_json(p) if d[0] == s0 and d[1] == s1]


def measure():
    log_path = f"/tmp/c4log-{random.random()}"
    log(f'starting task, to view log:\n\tkcd exec {os.environ["HOSTNAME"]} -- tail -f {log_path}')
    started = time.monotonic()
    with open(log_path, "w") as log_file:
        for line in sys.stdin:
            print(f"{str(int(time.monotonic()-started)).zfill(5)} {line}", end="", file=log_file)


def run_steps(ctx, steps):
    handlers = get_step_handlers()
    patt = re.compile(r'\{(\w+)\}')
    repl = (lambda a: ctx[a.group(1)] if a.group(1) in ctx else a.group(0))
    for step in steps:
        step_str = patt.sub(repl, json.dumps(step))
        log(step_str)
        op, *args = json.loads(step_str)
        ctx = handlers[op](ctx, *args)
    log("OK")


def clone_def_repo():
    return sn.clone_repo("C4INJECTION_REPO", os.environ["C4CRON_BRANCH"])


def main_operator(script):
    dir_life = clone_def_repo()
    last_tm_abbr = ""
    while True:
        tm = time.gmtime()
        tm_abbr = "ETKNRLP"[tm.tm_wday] + time.strftime("%H:%M", tm)
        if last_tm_abbr == tm_abbr:
            continue
        last_tm_abbr = tm_abbr
        run(("git", "pull"), cwd=dir_life.name)
        log(f"at {tm_abbr}")
        for act in load_def_list(dir_life, "weekly", tm_abbr):
            proc = Popen(
                (*py_cmd(), script, json.dumps([["call", act]])),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT
            )
            Popen((*py_cmd(), script, "measure"), stdin=proc.stdout)
        time.sleep(30)


def call(msg): run_steps(msg, one(*load_def_list(clone_def_repo(), "def", msg["op"])))


def get_step_handlers(): return {
    "dump": op_dump,
    "kube_contexts": lambda ctx, kube_contexts: {**ctx, "kube_contexts": set_kube_contexts(kube_contexts)},
    "snapshot_list_dump": lambda ctx, app: {**ctx, "": sn.snapshot_list_dump(ctx["kube_contexts"], app)},
    "snapshot_get": lambda ctx, app, name: {**ctx, "snapshot": sn.snapshot_get(ctx["kube_contexts"], app, name)},
    "snapshot_write": lambda ctx, dir_path: {**ctx, "": sn.snapshot_write(dir_path, *ctx["snapshot"])},
    "snapshot_read": lambda ctx, data_path_arg: {**ctx, "snapshot": sn.snapshot_read(data_path_arg)},
    "snapshot_put": lambda ctx, app: {**ctx, "": sn.snapshot_put(*ctx["snapshot"], ctx["kube_contexts"], app)},
    "snapshot_make": lambda ctx, app: {**ctx, "": sn.snapshot_make(ctx["kube_contexts"], app)},
    "snapshot_put_purged": lambda ctx, prefix_list: {
        **ctx, "": sn.snapshot_put_purged(*ctx["snapshot"], ctx["deploy_context"], prefix_list)
    },
    "injection_get": lambda ctx, branch, subdir: {**ctx, "injection": sn.injection_get(branch, subdir)},
    "injection_post": lambda ctx, app: {**ctx, "": sn.injection_post(ctx["injection"], ctx["kube_contexts"], app)},
    "injection_substitute": lambda ctx, fr, to: {**ctx, "injection": sn.injection_substitute(ctx["injection"], fr, to)},
    "injection_set": lambda ctx, value: {**ctx, "injection": value},
    "app_checkout": lambda ctx, branch: {**ctx, "app_dir_life": sn.clone_repo("C4APP_REPO", branch)},
    "app_start_purged": lambda ctx, apps: {
        **ctx, "": app_start_purged(ctx["deploy_context"], ctx["app_dir_life"].name, apps, *ctx["snapshot"])
    },
    "app_stop": lambda ctx, app: {**ctx, "": app_stop(ctx["deploy_context"], app)},
    "purge_mode_list": lambda ctx, mode_list: {**ctx, "": pu.purge_mode_list(ctx["deploy_context"], mode_list)},
    "purge_prefix_list": lambda ctx, prefix_list: {**ctx, "": pu.purge_prefix_list(ctx["deploy_context"], prefix_list)},
    "sleep": lambda ctx, sec: {**ctx, "": time.sleep(int(sec))},
    "call":
        lambda ctx, msg: {**ctx, "": call({"script": ctx["script"], "deploy_context": ctx["deploy_context"], **msg})},
    #"fire": lambda ctx, ev_abbr: {**ctx, "": fire(ctx["script"], clone_cron(), ev_abbr)},
}


def main(script, op): return (
    main_operator(script) if op == "main" else measure() if op == "measure" else
    run_steps({"script": script, "deploy_context": os.environ["C4DEPLOY_CONTEXT"]}, json.loads(op))
)


main(*sys.argv)
