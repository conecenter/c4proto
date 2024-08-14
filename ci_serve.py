
import random
import re
import sys
import os
import time
import tempfile
import subprocess
from json import dumps, loads, decoder as json_decoder

import c4util.snapshots as sn
import c4util.purge as pu
import c4util.cluster as cl
import c4util.git as git
import c4util.kube_reporter as kr
import c4util.notify as ny
from c4util import run, never, list_dir, log, Popen, wait_processes, changing_text, read_json, one, read_text, \
    never_if, need_dir, group_map


def py_cmd(): return "python3", "-u"


def fix_kube_env(e): return {**e, "KUBECONFIG": e["C4KUBECONFIG"]}


def app_prep(env, app, app_dir, up_path): return Popen(
    (*py_cmd(), "/ci_prep.py", "--context", app_dir, "--c4env", app, "--state", "main", "--info-out", up_path),
    env=fix_kube_env(env)
)


def app_up(env,up_path): run((*py_cmd(), "/ci_up.py"), stdin=open(up_path), env=fix_kube_env(env))


def app_cold_start_blocking(env, kube_context, app, app_dir, snapshot_from):
    out_dir_life = tempfile.TemporaryDirectory()
    up_path = f"{out_dir_life.name}/out.json"
    prep_proc = app_prep(env, app, app_dir, up_path)
    snapshot = sn.snapshot_get_outer(snapshot_from)
    wait_processes([prep_proc]) or never("prep failed")
    it = read_json(up_path)
    never_if(f'bad ctx {it["kube-context"]} of {it["c4env"]}' if it["kube-context"] != kube_context else None)
    stop_proc = app_stop_start(env, kube_context, it["c4env"])
    wait_processes([stop_proc]) or never("stop failed")
    install_prefix = one(*sn.get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", [
        man["spec"]["template"] for man in it["manifests"] if man["kind"] == "Deployment"
    ]))
    sn.snapshot_put_outer(snapshot, {"deploy_context": kube_context, "prefix": install_prefix})
    app_up(env, up_path)


def app_stop_start(env, kube_context, app):
    info = {"c4env": app, "state": "c4-off", "kube-context": kube_context, "manifests": []}
    proc = Popen((*py_cmd(), "/ci_up.py"), stdin=subprocess.PIPE, text=True, env=fix_kube_env(env))
    print(dumps(info), file=proc.stdin, flush=True)
    proc.stdin.close()
    return proc


def remote_call(env, kube_context, act):
    arg = dumps([["call", act]])
    label = env.get("C4CIO_LABEL", "c4cio")
    cmd = (*cl.get_any_pod_exec(cl.get_kubectl(kube_context), label), *py_cmd(), "/ci_serve.py", arg)
    proc = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    measure_inner(proc.stdout, sys.stdout)
    wait_processes([proc]) or never("failed")


def select_def(def_list, s0, s1): return [d[2] for d in def_list if d[0] == s0 and d[1] == s1]


def load_no_die(path):
    s = read_text(path)
    try:
        return loads(s)
    except json_decoder.JSONDecodeError as e:
        log(f"error parsing {path}: {e}")


def load_def_list(a_dir):
    return [d for p in list_dir(a_dir) if p.endswith(".json") for c in [load_no_die(p)] if c for d in c]


def log_message(env, log_path): return (
    'starting task' if log_path is None else
    f'starting task, to view log:\n\tkcd exec {env["HOSTNAME"]} -- tail -f {log_path}'
)


def start_log(env):
    log_path = f"/tmp/c4log-{random.random()}"
    log(log_message(env, log_path))
    return log_path


def measure(log_path):
    with open(log_path, "w") as log_file:
        measure_inner(sys.stdin, log_file)


def measure_inner(a_in, a_out):
    started = time.monotonic()
    for line in a_in:
        print(f"{str(int(time.monotonic()-started)).zfill(5)} {line}", end="", file=a_out, flush=True)


def find_def(scope, name):
    found = [st[2:] for st in scope[0] if st[0] == "def" and st[1] == name]
    # noinspection PyTypeChecker
    return (
        find_def(scope[1], name) if len(found) < 1 and scope[1] is not None else
        (*(None, *found[0])[-2:], scope) if len(found) == 1 else never(f"non-single {name}")
    )


def plan_steps(scope, planned):
    for op, *step_args in scope[0]:
        if op == "def":
            pass
        elif op == "for":
            items, body = step_args
            for it in items:
                planned = plan_steps((arg_substitute({"it": it}, body), scope), planned)
        elif op == "call":
            msg, = step_args
            name = msg["op"]
            args, c_scope, p_scope = find_def(scope, name)
            bad_args = [] if args is None else sorted(set(msg.keys()).symmetric_difference(["op", *args]))
            never_if([f"bad arg {arg} of {name}" for arg in bad_args])
            planned = plan_steps((arg_substitute(msg, c_scope), p_scope), planned)
        elif op == "call_once": # not fair, by name only
            name, = step_args
            if not any(s for s in planned if s[0] == "called" and s[1] == name):
                args, c_scope, p_scope = find_def(scope, name)
                never_if(None if args == [] else f"bad args of {name}")
                planned = plan_steps((c_scope, p_scope), (*planned, ("called", name)))
        else:
            planned = (*planned, (op, *step_args))
    return planned


def arg_substitute(args, body):
    patt = re.compile(r'\{(\w+)}|"@(\w+)"')
    repl = (lambda a: args.get(a.group(1), dumps(args[a.group(2)]) if a.group(2) in args else a.group(0)))
    return loads(patt.sub(repl, dumps(body)))


def distribution_run(groups, task_list, try_count, script, env, cwd, cmd):
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
            proc = start(start_log(env), script, l_cmd, l_cwd)
            started.append((group, task, proc))
        elif busy_groups:
            time.sleep(1)
        else:
            log(f"todo: {dumps(todo_tasks)}")
            break
    log("\n".join(f"distribution was {g} {t} {p.returncode}" for g, t, p in started))


def access(kube_context, k8s_path): return cl.secret_part_to_text(cl.get_kubectl(kube_context), k8s_path)


def clone_def_repo(env, a_dir):
    git.git_init(access(env["C4DEPLOY_CONTEXT"], env["C4CRON_REPO"]), a_dir)
    git.git_fetch_checkout(env["C4CRON_BRANCH"], a_dir, False)



def start(log_path, script, cmd, cwd):
    pr = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=cwd)
    Popen((*py_cmd(), script, dumps([["measure", log_path]])), stdin=pr.stdout)
    return pr


def start_steps(log_path, script, steps): return start(log_path, script, (*py_cmd(), script, dumps(steps)), None)


def main_operator(script, env):
    dir_life = tempfile.TemporaryDirectory()
    clone_def_repo(env, dir_life.name)
    last_tm_abbr = ""
    services = {}
    while True:
        tm = time.gmtime()
        tm_abbr = ("ETKNRLP"[tm.tm_wday], time.strftime("%H:%M", tm))
        if last_tm_abbr == tm_abbr:
            continue
        last_tm_abbr = tm_abbr
        run(("git", "pull"), cwd=dir_life.name)
        main_dir = env.get("C4CRON_MAIN_DIR")
        def_list = [] if main_dir is None else load_def_list(f'{dir_life.name}/{main_dir}')
        log(f"at {tm_abbr}")
        acts = [
            *select_def(def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
            *select_def(def_list, "daily", tm_abbr[1])
        ]
        for act in acts:
            log_path = start_log(env)
            start_steps(log_path, script, arg_substitute({"log_path": log_path}, [["call", act]]))
        for d in def_list:
            if d and d[0] == "service":
                nm = d[1]
                if nm not in services or services[nm].poll() is not None:
                    services[nm] = start_steps(start_log(env), script, d[2])
        time.sleep(30)


def kube_report_serve(script, d, a_dir):
    while True:
        wait_processes([Popen((
            *py_cmd(), script, dumps([["kube_report_make", kube_context, f"{a_dir}/{kube_context}.pods.txt"]])
        )) for kube_context in cl.get_all_contexts()])
        git.git_save_changed(d)
        time.sleep(30)


def with_kube_contexts(deploy_context, opt): return (
    {**opt, "kube_contexts": [deploy_context]} if "app" in opt and "kube_contexts" not in opt else
    {**opt, "deploy_context": deploy_context} if "prefix" in opt else opt
)


def get_step_handlers(script, env, deploy_context, get_dir, register, registered): return {
    "#": lambda *args: None,
    "called": lambda *args: None,
    "kube_contexts": lambda out_path: changing_text(out_path, dumps(cl.get_all_contexts())),

    "snapshot_list_dump": lambda opt: sn.snapshot_list_dump(with_kube_contexts(deploy_context, opt)),
    "snapshot_copy": lambda opt: sn.snapshot_put_outer(
        sn.snapshot_get_outer(with_kube_contexts(deploy_context, opt["from"])),
        with_kube_contexts(deploy_context, opt["to"])
    ),
    "snapshot_make": lambda opt: sn.snapshot_make(with_kube_contexts(deploy_context, opt)),
    "injection_make": lambda opt: sn.injection_make(
        get_dir(opt["from"]), opt.get("substitute", []), with_kube_contexts(deploy_context, opt["to"])
    ),
    "empty_dir": lambda name: need_dir(get_dir(name)),
    "git_repo": lambda name, k8s_path: git.git_init(access(deploy_context, k8s_path), need_dir(get_dir(name))),
    "git_init": lambda name: None,
    "git_clone": lambda name, br: git.git_fetch_checkout(br, get_dir(name), False),
    "git_clone_or_init": lambda name, br: git.git_fetch_checkout(br, get_dir(name), True),
    "git_add_tagged": lambda cwd, tag: git.git_add_tagged(get_dir(cwd), tag),
    "app_cold_start_blocking": lambda args: app_cold_start_blocking(env,*args),
    "app_cold_start": lambda opt: register("proc", start_steps(start_log(env), script, [[
        "app_cold_start_blocking",
        [deploy_context, opt["app"], get_dir(opt["ver"]), with_kube_contexts(deploy_context, opt["snapshot_from"])]
    ]])),
    "app_stop_start": lambda kube_context, app: register("proc", app_stop_start(env, kube_context, app)),
    "purge_mode_list": lambda mode_list: pu.purge_mode_list(deploy_context, mode_list),
    "purge_prefix_list": lambda prefix_list: pu.purge_prefix_list(deploy_context, prefix_list),
    "run": lambda cwd, cmd: run(cmd, cwd=get_dir(cwd)),
    "remote_call": lambda msg: remote_call(env, deploy_context, msg),
    "start": lambda cwd, cmd: register("proc", start(start_log(env), script, cmd, get_dir(cwd))),
    "wait_all": lambda: wait_processes(registered("proc")),
    "wait_all_ok": lambda: wait_processes(registered("proc")) or never("failed"),
    "rsync": lambda fr, to: run(("rsync", "-acr", get_dir(fr)+"/", need_dir(get_dir(to))+"/")),
    "kube_report_make": lambda kube_context, out_path: changing_text(
        out_path, kr.get_cluster_report(cl.get_pods_json(cl.get_kubectl(kube_context), ()))
    ),
    "kube_report_serve": lambda name, subdir: kube_report_serve(
        script, get_dir(name), need_dir(get_dir(f'{name}/{subdir}'))
    ),
    "notify_started": lambda opt: register(
        "notify_succeeded", ny.notify_started(
            (*py_cmd(), script, dumps([["notify_wait_finish"]])),
            ny.notify_create_requests(
                access(deploy_context, opt["auth"]), opt["url"],
                time.time(), opt["work_hours"], opt["valid_hours"], log_message(env, opt.get("log_path"))
            )
        )
    ),
    "notify_succeeded": lambda: [f() for f in registered("notify_succeeded")],
    "notify_wait_finish": lambda: ny.notify_wait_finish(),
    "main": lambda: main_operator(script, env),
    "measure": lambda log_path: measure(log_path),
    "distribution_run": lambda opt: distribution_run(
        opt["groups"],
        loads(read_text(get_dir(opt["tasks"]))) if isinstance(opt["tasks"], str) else opt["tasks"],
        opt["try_count"], script, env, get_dir(opt["dir"]), opt["command"]
    ),
    "secret_get": lambda fn, k8s_path: changing_text(get_dir(fn), access(deploy_context, k8s_path)),
}


def main(env, script, op):
    ctx = {}
    steps = loads(op) if op.startswith("[") else [[op]]
    need_plan = any(s[0] == "call" for s in steps)
    tmp_life = None
    if need_plan:
        tmp_life = tempfile.TemporaryDirectory()
        def_repo_dir = need_dir(f"{tmp_life.name}/def_repo")
        clone_def_repo(env, def_repo_dir)
        steps = plan_steps((steps, (load_def_list(f'{def_repo_dir}/{env["C4CRON_UTIL_DIR"]}'), None)), ())
        log("plan:\n" + "\n".join(f"\t{dumps(step)}" for step in steps))
    get_dir = (lambda subdir: f'{tmp_life.name}/{subdir}')
    register = (lambda k, v: ctx.setdefault(k, []).append(v))
    registered = (lambda k: ctx.get(k, []))
    handlers = get_step_handlers(script, env, env["C4DEPLOY_CONTEXT"], get_dir, register, registered)
    for step in steps:
        if need_plan:
            log(dumps(step))
        handlers[step[0]](*step[1:])
    log("OK")


main(os.environ, *sys.argv)
