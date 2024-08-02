
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
    never_if, need_dir, group_map, run_text_out


def py_cmd(): return "python3", "-u"


def fix_kube_env(e): return {**e, "KUBECONFIG": e["C4KUBECONFIG"]}


def app_prep(app, app_dir, up_path): return Popen(
    (*py_cmd(), "/ci_prep.py", "--context", app_dir, "--c4env", app, "--state", "main", "--info-out", up_path),
    env=fix_kube_env(os.environ)
)


def app_up(up_path): run((*py_cmd(), "/ci_up.py"), stdin=open(up_path), env=fix_kube_env(os.environ))


def app_purged_start_blocking(kube_context, app, app_dir, kube_contexts, snapshot_from_app, try_count):
    out_dir_life = tempfile.TemporaryDirectory()
    up_path = f"{out_dir_life.name}/out.json"
    prep_proc = app_prep(app, app_dir, up_path)
    snapshot = sn.snapshot_get(kube_contexts, snapshot_from_app, "last", try_count)
    wait_processes([prep_proc]) or never("prep failed")
    it = read_json(up_path)
    never_if(f'bad ctx {it["kube-context"]} of {it["c4env"]}' if it["kube-context"] != kube_context else None)
    stop_proc = app_stop_start(kube_context, it["c4env"])
    wait_processes([stop_proc]) or never("stop failed")
    install_prefix = one(*sn.get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", [
        man["spec"]["template"] for man in it["manifests"] if man["kind"] == "Deployment"
    ]))
    kc = cl.get_kubectl(kube_context)
    while install_prefix in pu.get_active_prefixes(kc):
        time.sleep(2)
    pu.purge_prefix_list(kube_context, [install_prefix])
    wait_no_topic(install_prefix)
    mc = sn.s3init(kc)
    sn.snapshot_put_purged(*snapshot, mc, install_prefix)
    app_up(up_path)


def wait_no_topic(prefix):
    cmd = ("kafkacat", "-L", "-J", "-F", os.environ["C4KCAT_CONFIG"])
    topic = f"{prefix}.inbox"
    while any(t["topic"] == topic for t in json.loads(run_text_out(cmd))["topics"]):
        time.sleep(2)


def app_stop_start(kube_context, app):
    info = {"c4env": app, "state": "c4-off", "kube-context": kube_context, "manifests": []}
    proc = Popen((*py_cmd(), "/ci_up.py"), stdin=subprocess.PIPE, text=True, env=fix_kube_env(os.environ))
    print(json.dumps(info), file=proc.stdin, flush=True)
    proc.stdin.close()
    return proc


def remote_call(kube_context, act):
    arg = json.dumps([["call", act]])
    label = os.environ.get("C4CIO_LABEL", "c4cio")
    cmd = (*cl.get_any_pod_exec(cl.get_kubectl(kube_context), label), *py_cmd(), "/ci_serve.py", arg)
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


def log_message(log_path): return (
    'starting task' if log_path is None else
    f'starting task, to view log:\n\tkcd exec {os.environ["HOSTNAME"]} -- tail -f {log_path}'
)


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
    patt = re.compile(r'\{(\w+)}|"@(\w+)"')
    repl = (lambda a: args.get(a.group(1), json.dumps(args[a.group(2)]) if a.group(2) in args else a.group(0)))
    return json.loads(patt.sub(repl, json.dumps(body)))


def call(ctx, msg):
    op = msg["op"]
    if op not in ctx:
        ctx = run_steps(ctx, [d for d in load_def_list(clone_def_repo()) if d[0] == "def"])
    args_body = ctx[op]
    if isinstance(args_body, tuple):
        args, body = args_body
        never_if([f"bad arg {arg} of {op}" for arg in sorted(set(msg.keys()).symmetric_difference(["op", *args]))])
    else:
        body = args_body
    return run_steps(ctx, arg_substitute(msg, body))


def handle_for(ctx, items, body):
    for it in items:
        ctx = run_steps(ctx, arg_substitute({"it": it}, body))
    return ctx


def distribution_run(groups, task_list, try_count, script, cwd, cmd):
    started = []
    while True:
        busy_groups = {g for g, t, p in started if p.returncode is None and p.poll() is None}
        not_todo_tasks = {t for g, t, p in started if p.returncode is None or p.returncode == 0}
        task_failures = [t for g, t, p in started if p.returncode is not None and p.returncode != 0]
        task_failure_counts = {t: len(l) for t, l in group_map(task_failures, lambda t: (t, 1)).items()}
        todo_tasks = [t for t in task_list if t not in not_todo_tasks]
        prior_tasks = sorted((c, t) for t in todo_tasks for c in [task_failure_counts.get(t, 0)] if c < try_count)
        started_set = {(g, t) for g, t, p in started}
        gt_iter = ((g, t) for g in groups if g not in busy_groups for c, t in prior_tasks if (g, t) not in started_set)
        group_task = next(gt_iter, None)
        if group_task is not None:
            group, task = group_task
            l_cwd, l_cmd = arg_substitute({"group": group, "task": task}, [cwd, cmd])
            proc = start(start_log(), script, l_cmd, cwd=l_cwd)
            started.append((group, task, proc))
        elif busy_groups:
            time.sleep(1)
        else:
            log(f"todo: {json.dumps(todo_tasks)}")
            break
    log("\n".join(f"distribution was {g} {t} {p.returncode}" for g, t, p in started))


def access(kube_context, k8s_path): return cl.secret_part_to_text(cl.get_kubectl(kube_context), k8s_path)


def clone_def_repo():
    repo = access(os.environ["C4DEPLOY_CONTEXT"], os.environ["C4CRON_REPO"])
    dir_life = tempfile.TemporaryDirectory()
    git.git_init(repo, dir_life.name)
    git.git_fetch_checkout(os.environ["C4CRON_BRANCH"], dir_life.name, False)
    return dir_life


def start(log_path, script, cmd, **options):
    pr = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **options)
    Popen((*py_cmd(), script, json.dumps([["measure", log_path]])), stdin=pr.stdout)
    return pr


def start_steps(script, steps):
    log_path = start_log()
    return start(log_path, script, (*py_cmd(), script, json.dumps([["def", "log_path", log_path], *steps])))


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


def setup_dir(ctx, subdir, f):
    life = ctx.get("tmp_life", tempfile.TemporaryDirectory())
    f(need_dir(f"{life.name}/{subdir}"))
    return {"tmp_life": life}


def get_dir(ctx, subdir): return f'{ctx["tmp_life"].name}/{subdir}'


def kube_report_serve(script, d, a_dir):
    while True:
        wait_processes([Popen((
            *py_cmd(), script, json.dumps([["kube_report_make", kube_context, f"{a_dir}/{kube_context}.pods.txt"]])
        )) for kube_context in cl.get_all_contexts()])
        git.git_save_changed(d)
        time.sleep(30)


def setup_started(ctx, proc): return {"proc": (*ctx.get("proc", []), proc)}


def get_step_handlers(): return ({
    "call": call, "for": handle_for, "#": lambda ctx, *args: ctx
}, {
    "kube_contexts": lambda ctx, kube_contexts: {
        "kube_contexts": (cl.get_all_contexts() if kube_contexts == "all" else kube_contexts)
    },
    "snapshot_list_dump": lambda ctx, app: {"": sn.snapshot_list_dump(ctx["kube_contexts"], app)},
    "snapshot_get": lambda ctx, app, name: {
        "snapshot": sn.snapshot_get(ctx["kube_contexts"], app, name, int(ctx.get("snapshot_try_count", 3)))
    },
    "snapshot_write": lambda ctx, dir_path: {"": sn.snapshot_write(dir_path, *ctx["snapshot"])},
    "snapshot_read": lambda ctx, data_path_arg: {"snapshot": sn.snapshot_read(data_path_arg)},
    "snapshot_ignore": lambda ctx, ignore: {"snapshot_ignore": ignore},
    "snapshot_put": lambda ctx, app: {
        "": sn.snapshot_put(*ctx["snapshot"], ctx["kube_contexts"], app, ctx.get("snapshot_ignore", ""))
    },
    "snapshot_make": lambda ctx, app: {"": sn.snapshot_make(ctx["kube_contexts"], app)},
    "snapshot_put_purged": lambda ctx, prefix: {
        "": sn.snapshot_put_purged(*ctx["snapshot"], sn.s3init(cl.get_kubectl(ctx["deploy_context"])), prefix)
    },
    "injection_suffix": lambda ctx, suffix: {"injection_suffix": suffix},
    "injection_get": lambda ctx, *subdir: {
        "injection": sn.injection_get(get_dir(ctx, '/'.join(subdir)), ctx["injection_suffix"])
    },
    "injection_post": lambda ctx, app: {"": sn.injection_post(ctx["injection"], ctx["kube_contexts"], app)},
    "injection_substitute": lambda ctx, fr, to: {"injection": sn.injection_substitute(ctx["injection"], fr, to)},
    "injection_dump": lambda ctx: {"": log(f'injection:\n{ctx["injection"]}')},
    "empty_dir": lambda ctx, name: setup_dir(ctx, name, lambda d: ()),
    "git_repo": lambda ctx, name, k8s_path: setup_dir(
        ctx, name, lambda d: git.git_init(access(ctx["deploy_context"], k8s_path), d)
    ),
    "git_clone": lambda ctx, name, br: {"": git.git_fetch_checkout(br, get_dir(ctx, name), False)},
    "git_clone_or_init": lambda ctx, name, br: {"": git.git_fetch_checkout(br, get_dir(ctx, name), True)},
    "git_add_tagged": lambda ctx, cwd, tag: {"": git.git_add_tagged(get_dir(ctx, cwd), tag)},
    "app_purged_start_blocking": lambda ctx, opt: {"": app_purged_start_blocking(
        ctx["deploy_context"], opt["app"], opt["app_dir"],
        opt["kube_contexts"], opt["snapshot_from"], opt["try_count"]
    )},
    "app_purged_start": lambda ctx, opt: setup_started(ctx, start_steps(ctx["script"], [
        ["app_purged_start_blocking", {
            "app": opt["app"], "app_dir": get_dir(ctx, opt["ver"]),
            "kube_contexts": ctx["kube_contexts"], "snapshot_from": opt["snapshot_from"], "try_count": opt["try_count"]
        }]
    ])),
    "app_stop_start": lambda ctx, kube_context, app: setup_started(ctx, app_stop_start(kube_context, app)),
    "purge_mode_list": lambda ctx, mode_list: {"": pu.purge_mode_list(ctx["deploy_context"], mode_list)},
    "purge_prefix_list": lambda ctx, prefix_list: {"": pu.purge_prefix_list(ctx["deploy_context"], prefix_list)},
    "run": lambda ctx, cwd, cmd: {"": run(cmd, cwd=get_dir(ctx, cwd))},
    "remote_call": lambda ctx, msg: {"": remote_call(ctx["deploy_context"], msg)},
    "start": lambda ctx, cwd, cmd: setup_started(ctx, start(start_log(), ctx["script"], cmd, cwd=get_dir(ctx, cwd))),
    "wait_all": lambda ctx, need_ok: {"": wait_processes(ctx.get("proc", [])) or not need_ok or never("failed")},
    "rsync": lambda ctx, fr, to: {"": run(("rsync", "-acr", get_dir(ctx, fr)+"/", need_dir(get_dir(ctx, to))+"/"))},
    "kube_report_make": lambda ctx, kube_context, out_path: {
        "": changing_text(out_path, kr.get_cluster_report(cl.get_pods_json(cl.get_kubectl(kube_context), ())))
    },
    "kube_report_serve": lambda ctx, name, subdir: {
        "": kube_report_serve(ctx["script"], get_dir(ctx, name), need_dir(get_dir(ctx, f'{name}/{subdir}')))
    },
    "notify_started": lambda ctx, opt: {
        "notify_succeeded": ny.notify_started(
            (*py_cmd(), ctx["script"], json.dumps([["notify_wait_finish"]])),
            ny.notify_create_requests(
                access(ctx["deploy_context"], opt["auth"]), opt["url"],
                time.time(), opt["work_hours"], opt["valid_hours"], log_message(ctx.get("log_path"))
            )
        )
    },
    "notify_succeeded": lambda ctx: {"": ctx["notify_succeeded"]()},
    "notify_wait_finish": lambda ctx: {"": ny.notify_wait_finish()},
    "main": lambda ctx: {"": main_operator(ctx["script"])},
    "measure": lambda ctx, log_path: {"": measure(log_path)},
    "def": lambda ctx, k, *v: {k: v[0] if len(v) == 1 else tuple(v) if len(v) == 2 else never(f"bad def {k} {v}")},
    "distribution_run": lambda ctx, opt: {
        "": distribution_run(
            opt["groups"], opt["tasks"], opt["try_count"], ctx["script"], get_dir(ctx, opt["dir"]), opt["command"]
        )
    },
    "secret_get": lambda ctx, fn, k8s_path: {
        "": changing_text(get_dir(ctx, fn), access(ctx["deploy_context"], k8s_path))
    },
})


def main(script, op):
    ctx = {"script": script, "deploy_context": os.environ["C4DEPLOY_CONTEXT"]}
    run_steps(ctx, json.loads(op) if op.startswith("[") else [[op]])
    log("OK")


main(*sys.argv)
