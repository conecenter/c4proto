
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
import c4util.kube_reporter as kr
import c4util.notify as ny
from c4util import run, never, list_dir, log, Popen, wait_processes, changing_text, read_json, one, read_text, \
    never_if, need_dir


def py_cmd(): return "python3", "-u"


def fix_kube_env(e): return {**e, "KUBECONFIG": e["C4KUBECONFIG"]}


def app_prep(apps, get_app_dir):
    out_dir_life = tempfile.TemporaryDirectory()
    for app in apps:
        up_path = f"{out_dir_life.name}/{app}.json"
        env = fix_kube_env(os.environ)
        app_dir = get_app_dir(app)
        cmd = (*py_cmd(), "/ci_prep.py", "--context", app_dir, "--c4env", app, "--state", "main", "--info-out", up_path)
        run(cmd, env=env)
    return out_dir_life


def app_start_purged(kube_context, apps, get_app_dir, get_app_snapshot):
    out_dir_life = app_prep(apps, get_app_dir)
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


def remote_call(kube_context, act):
    arg = json.dumps([["call", act]])
    cmd = (*cl.get_any_pod_exec(cl.get_kubectl(kube_context), "c4cio"), *py_cmd(), "/ci_serve.py", arg)
    proc = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    measure_inner(proc.stdout, sys.stdout)
    wait_processes([proc]) or never("failed")


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


def log_message(log_path):
    return f'starting task, to view log:\n\tkcd exec {os.environ["HOSTNAME"]} -- tail -f {log_path}'


def start_log():
    log_path = f"/tmp/c4log-{random.random()}"
    log(log_message(log_path))
    return log_path


def measure(log_path):
    with open(log_path, "w") as log_file:
        measure_inner(sys.stdin, log_file)


def measure_inner(a_in, a_out):
    started = time.monotonic()
    for line in a_in:
        print(f"{str(int(time.monotonic()-started)).zfill(5)} {line}", end="", file=a_out, flush=True)


def run_steps(ctx, steps):
    full_handlers, handlers = get_step_handlers()
    for op, *step_args in steps:
        log(json.dumps([op, *step_args]))
        ctx = full_handlers[op](ctx, *step_args) if op in full_handlers else {**ctx, **handlers[op](ctx, *step_args)}
    return ctx


def arg_substitute(args, body):
    patt = re.compile(r'\{(\w+)}')
    repl = (lambda a: args[a.group(1)] if a.group(1) and a.group(1) in args else a.group(0))
    return json.loads(patt.sub(repl, json.dumps(body)))


def call(ctx, msg):
    op = msg["op"]
    body = ctx.get(op)
    if body is None:
        if "def_list" not in ctx:
            ctx = {**ctx, "def_list": load_def_list(clone_def_repo())}
        body = one(*select_def(ctx["def_list"], "def", op))
    return run_steps(ctx, arg_substitute(msg, body))


def handle_for(ctx, list_name, body):
    for it in ctx[list_name]:
        ctx = run_steps(ctx, arg_substitute({"it": it}, body))
    return ctx


def distribution_run(script, groups, task_list, cwd, cmd, resolve_dir):
    tasks = iter(task_list)
    processes = [(group, None) for group in groups]
    mk_proc = (lambda l_cwd, l_cmd: start(start_log(), script, l_cmd, cwd=resolve_dir(l_cwd)))
    replace = (lambda group, task: None if task is None else mk_proc(
        *arg_substitute({"group": group, "task": task}, [cwd, cmd])
    ))
    while True:
        processes = [(gr, pr if pr and pr.poll() is None else replace(gr, next(tasks, None))) for gr, pr in processes]
        if all(proc is None for group, proc in processes):
            return
        time.sleep(1)


def access(kube_context, k8s_path): return cl.secret_part_to_text(cl.get_kubectl(kube_context), k8s_path)


def clone_def_repo():
    repo = access(os.environ["C4DEPLOY_CONTEXT"], os.environ["C4CRON_REPO"])
    dir_life = tempfile.TemporaryDirectory()
    git.git_clone(repo, os.environ["C4CRON_BRANCH"], dir_life.name)
    return dir_life


def start(log_path, script, cmd, **options):
    pr = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **options)
    Popen((*py_cmd(), script, json.dumps([["measure", log_path]])), stdin=pr.stdout)
    return pr


def start_steps(script, steps):
    log_path = start_log()
    return start(log_path, script, (*py_cmd(), script, json.dumps([["log_path", log_path], *steps])))


def main_operator(script):
    dir_life = clone_def_repo()
    last_tm_abbr = ""
    services = {}
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
            start_steps(script, [["call", act]])
        for d in def_list:
            if d and d[0] == "service":
                nm = d[1]
                if nm not in services or services[nm].poll() is not None:
                    services[nm] = start_steps(script, d[2])
        time.sleep(30)


def setup_dir(f):
    life = tempfile.TemporaryDirectory()
    f(life.name)
    return life


def kube_report_serve(script, d, a_dir):
    while True:
        wait_processes([Popen((
            *py_cmd(), script, json.dumps([["kube_report_make", kube_context, f"{a_dir}/{kube_context}.pods.txt"]])
        )) for kube_context in cl.get_all_contexts()])
        git.git_save_changed(d)
        time.sleep(30)


def get_step_handlers(): return ({
    "call": call, "for": handle_for, "#": lambda ctx, *args: ctx
}, {
    "kube_contexts": lambda ctx, kube_contexts: {
        "kube_contexts": (cl.get_all_contexts() if kube_contexts == "all" else kube_contexts)
    },
    "snapshot_list_dump": lambda ctx, app: {"": sn.snapshot_list_dump(ctx["kube_contexts"], app)},
    "snapshot_get": lambda ctx, app, name: {
        "snapshot": sn.snapshot_get(ctx["kube_contexts"], app, name, ctx.get("snapshot_try_count", 3))
    },
    "snapshot_write": lambda ctx, dir_path: {"": sn.snapshot_write(dir_path, *ctx["snapshot"])},
    "snapshot_read": lambda ctx, data_path_arg: {"snapshot": sn.snapshot_read(data_path_arg)},
    "snapshot_ignore": lambda ctx, ignore: {"snapshot_ignore": ignore},
    "snapshot_put": lambda ctx, app: {
        "": sn.snapshot_put(*ctx["snapshot"], ctx["kube_contexts"], app, ctx.get("snapshot_ignore", ""))
    },
    "snapshot_make": lambda ctx, app: {"": sn.snapshot_make(ctx["kube_contexts"], app)},
    "snapshot_will": lambda ctx, app: {f"snapshot-{app}": ctx["snapshot"]},
    "snapshot_put_purged": lambda ctx, prefix: {
        "": sn.snapshot_put_purged(*ctx["snapshot"], sn.s3init(cl.get_kubectl(ctx["deploy_context"])), prefix)
    },
    "injection_suffix": lambda ctx, suffix: {"injection_suffix": suffix},
    "injection_get": lambda ctx, name, subdir: {
        "injection": sn.injection_get(f'{ctx[name].name}/{subdir}', ctx["injection_suffix"])
    },
    "injection_post": lambda ctx, app: {"": sn.injection_post(ctx["injection"], ctx["kube_contexts"], app)},
    "injection_substitute": lambda ctx, fr, to: {"injection": sn.injection_substitute(ctx["injection"], fr, to)},
    "injection_set": lambda ctx, value: {"injection": value},
    "injection_dump": lambda ctx: {"": log(f'injection:\n{ctx["injection"]}')},
    "empty_dir": lambda ctx, name: {name: setup_dir(lambda d: ())},
    "git_repo": lambda ctx, name, k8s_path: {f"repo-{name}": k8s_path},
    "git_clone": lambda ctx, name, branch: {
        name: setup_dir(lambda d: git.git_clone(access(ctx["deploy_context"], ctx[f"repo-{name}"]), branch, d))
    },
    "git_init": lambda ctx, name: {
        name: setup_dir(lambda d: git.git_init(access(ctx["deploy_context"], ctx[f"repo-{name}"]), d))
    },
    "git_clone_or_init": lambda ctx, name, br: {
        name: setup_dir(lambda d: git.git_clone_or_init(access(ctx["deploy_context"], ctx[f"repo-{name}"]), br, d))
    },
    "git_add_tagged": lambda ctx, cwd, tag: {"": git.git_add_tagged(ctx[cwd].name, tag)},
    "app_ver": lambda ctx, app, cwd: {app: ctx[cwd], "app_to_start": [*ctx.get("app_to_start", []), app]},
    "app_start_purged": lambda ctx, *apps: {
        "": app_start_purged(
            ctx["deploy_context"], apps[0], lambda app: ctx[app].name, lambda app: ctx[f"snapshot-{app}"]
        )
    } if apps else {
        "app_to_start": [],
        "": app_start_purged(
            ctx["deploy_context"], ctx["app_to_start"], lambda app: ctx[app].name, lambda app: ctx[f"snapshot-{app}"]
        )
    },
    "app_stop": lambda ctx, kube_context, app: {"": app_stop(kube_context, app)},
    "purge_mode_list": lambda ctx, mode_list: {"": pu.purge_mode_list(ctx["deploy_context"], mode_list)},
    "purge_prefix_list": lambda ctx, prefix_list: {"": pu.purge_prefix_list(ctx["deploy_context"], prefix_list)},
    "run": lambda ctx, cwd, cmd: {"": run(cmd, cwd=ctx[cwd].name)},
    "remote_call": lambda ctx, msg: {"": remote_call(ctx["deploy_context"], msg)},
    "start": lambda ctx, cwd, cmd: {
        "proc": (*ctx.get("proc", []), start(start_log(), ctx["script"], cmd, cwd=ctx[cwd].name))
    },
    "wait_all": lambda ctx: {"all_ok": wait_processes(ctx.get("proc", []))},
    "rsync_files": lambda ctx, rsync_files: {"rsync_files": "".join(f"{f}\n" for f in rsync_files)},
    "rsync_add": lambda ctx, fr, to: {
        "": run(
            ("rsync", "-acr", "--files-from", "-", ctx[fr].name, ctx[to].name), text=True, input=ctx["rsync_files"]
        )
    },
    "kube_report_make": lambda ctx, kube_context, out_path: {
        "": changing_text(out_path, kr.get_cluster_report(cl.get_pods_json(cl.get_kubectl(kube_context), ())))
    },
    "kube_report_serve": lambda ctx, name, subdir: {
        "": kube_report_serve(ctx["script"], ctx[name].name, need_dir(f'{ctx[name].name}/{subdir}'))
    },
    "notify_auth": lambda ctx, auth: {"notify_auth": auth},
    "notify_url": lambda ctx, url: {"notify_url": url},
    "notify_started": lambda ctx, work_hours, valid_hours: {
        "notify_succeeded": ny.notify_started(
            (*py_cmd(), ctx["script"], json.dumps([["notify_wait_finish"]])),
            ny.notify_create_requests(
                access(ctx["deploy_context"], ctx["notify_auth"]), ctx["notify_url"],
                time.time(), work_hours, valid_hours, log_message(ctx["log_path"])
            )
        )
    },
    "notify_succeeded": lambda ctx: {"": ctx["notify_succeeded"]()},
    "notify_wait_finish": lambda ctx: {"": ny.notify_wait_finish()},
    "main": lambda ctx: {"": main_operator(ctx["script"])},
    "measure": lambda ctx, log_path: {"": measure(log_path)},
    "log_path": lambda ctx, log_path: {"log_path": log_path},
    "def": lambda ctx, name, value: {name: value},
    "distribution_run": lambda ctx, cwd, cmd: {
        "": distribution_run(
            ctx["script"], ctx["distribution_groups"], ctx["distribution_tasks"], cwd, cmd, lambda cw: ctx[cw].name
        )
    },
})


def main(script, op):
    ctx = {"script": script, "deploy_context": os.environ["C4DEPLOY_CONTEXT"]}
    run_steps(ctx, json.loads(op) if op.startswith("[") else [[op]])
    log("OK")


main(*sys.argv)
