
import random
import sys
import os
import time
import tempfile
import subprocess
from json import dumps, loads, decoder as json_decoder
import pathlib

from . import snapshots as sn, purge as pu, cluster as cl, git, kube_reporter as kr, notify as ny, distribution
from .cio_preproc import arg_substitute, plan_steps
from . import run, never, list_dir, log, Popen, wait_processes, changing_text, one, read_text, never_if, need_dir, \
    path_exists


def py_cmd(): return "python3", "-u"


def app_prep_start(env, app, app_dir, up_path):
    proto_dir = env["C4CI_PROTO_DIR"]
    args = ("--context", app_dir, "--c4env", app, "--state", "main", "--info-out", up_path)
    cmd = (*py_cmd(), f'{proto_dir}/ci_prep.py', *args)
    return start(start_log(env), script, cmd, env={**env, "KUBECONFIG": env["C4KUBECONFIG"]})


def app_up(it):
    prune_list = [f"--prune-whitelist={v}" for v in (
        "/v1/Service", "apps/v1/Deployment", "apps/v1/StatefulSet",
        "networking.k8s.io/v1/Ingress", "extensions/v1beta1/Ingress"
    )]
    cmd = (*cl.get_kubectl(it["kube-context"]), "apply", "--prune", *prune_list, "-l", f'c4env={it["c4env"]}', "-f-")
    run(cmd, text=True, input="\n".join(dumps(v) for v in it["manifests"]))


def wait_load(path):
    while True:
        it = load_no_die(path) if path_exists(path) else None
        if it is not None:
            return it
        time.sleep(1)

# for stop
# for prep
# for wait_no_pods
# for cold_up
# distribution

# todo: wait_no_pods

def app_cold_start_blocking(env, conf_from, snapshot_from):
    it =  wait_load(conf_from)
    never_if(f'bad ctx {it["kube-context"]} {it["c4env"]}' if it["kube-context"] != env["C4DEPLOY_CONTEXT"] else None)
    pod_templates = [man["spec"]["template"] for man in it["manifests"] if man["kind"] == "Deployment"]
    install_prefix = one(*sn.get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", pod_templates))
    sn.snapshot_copy(env, snapshot_from, {"prefix": install_prefix})
    app_up(it)


def app_stop_start(kube_context, app):
    return Popen((*cl.get_kubectl(kube_context), "delete", "service,deploy,statefulset,ingress", "-l", f'c4env={app}'))


def remote_call(env, kube_context, steps):
    label = env.get("C4CIO_LABEL", "c4cio")
    cmd = (*cl.get_any_pod_exec(cl.get_kubectl(kube_context), label), *py_cmd(), "/ci_serve.py", dumps(steps))
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
    f'starting task, to view log:\n\tkcd exec {env["HOSTNAME"]} -- tail -f {log_path} -n1000'
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


def access(kube_context, k8s_path): return cl.secret_part_to_text(cl.get_kubectl(kube_context), k8s_path)


def clone_def_repo(env, a_dir):
    git.git_clone(access(env["C4DEPLOY_CONTEXT"], env["C4CRON_REPO"]), env["C4CRON_BRANCH"], a_dir)


def start(log_path, script, cmd, cwd=None, env=None):
    pr = Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=cwd, env=env)
    subprocess.Popen(script([["measure", log_path]]), stdin=pr.stdout)
    return pr


def start_steps(log_path, script, steps):
    return start(log_path, script, script(arg_substitute({"log_path": log_path}, steps)))


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
        git.git_pull(dir_life.name)
        def_list = load_def_list(f'{dir_life.name}/{env["C4CRON_MAIN_DIR"]}')
        log(f"at {tm_abbr}")
        acts = [
            *select_def(def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
            *select_def(def_list, "daily", tm_abbr[1])
        ]
        for act in acts:
            start_steps(start_log(env), script, [["call", act]])
        for d in def_list:
            if d and d[0] == "service":
                nm = d[1]
                if nm not in services or services[nm].poll() is not None:
                    services[nm] = start_steps(start_log(env), script, [["call", d[2]]])
        time.sleep(30)


def kube_report_serve(script, d, subdir_pf):
    while True:
        life = tempfile.TemporaryDirectory()
        wait_processes([Popen(
            script([["kube_report_make", kube_context, f"{life.name}/{kube_context}.pods.txt"]])
        ) for kube_context in cl.get_all_contexts()])
        git.git_pull(d)
        run(("rsync", "-acr", "--exclude", ".git", f"{life.name}/", f'{need_dir(f"{d}/{subdir_pf}")}/'))
        git.git_save_changed(d)
        time.sleep(30)


def access_once(deploy_context, d):
    path = f"{d}/.c4k8s_path"
    res = access(deploy_context, read_text(path))
    pathlib.Path(path).unlink()
    return res


def get_step_handlers(script, env, deploy_context, get_dir, register, registered): return {
    "#": lambda *args: None,
    "called": lambda *args: None,
    "snapshot_list_dump": lambda opt: sn.snapshot_list_dump(deploy_context, opt),
    "snapshot_copy": lambda opt: sn.snapshot_copy(env, opt["from"], opt["to"]),
    "snapshot_make": lambda opt: sn.snapshot_make(deploy_context, opt),
    "injection_make": lambda opt: sn.injection_make(
        deploy_context, get_dir(opt["from"]), opt.get("substitute", []), opt["to"]
    ),
    "empty_dir": lambda name: need_dir(get_dir(name)),
    "git_repo": lambda name, k8s_path: changing_text(f'{need_dir(get_dir(name))}/.c4k8s_path', k8s_path),
    "git_init": lambda name: git.git_init(access_once(deploy_context, get_dir(name)), get_dir(name)),
    "git_clone": lambda name, br: git.git_clone(access_once(deploy_context, get_dir(name)), br, get_dir(name)),
    "git_clone_or_init": lambda name, br: (
        git.git_clone_or_init(access_once(deploy_context, get_dir(name)), br, get_dir(name))
    ),
    "git_add_tagged": lambda cwd, tag: git.git_add_tagged(get_dir(cwd), tag),
    "app_cold_start_blocking": lambda args: app_cold_start_blocking(env, *args),
    "app_cold_start": lambda opt: register("proc", start_steps(start_log(env), script, [[
        "app_cold_start_blocking", [get_dir(opt["conf_from"]), opt["snapshot_from"]]
    ]])),
    "app_stop_start": lambda kube_context, app: register("proc", app_stop_start(kube_context, app)),
    "app_prep_start": lambda opt: register(
        "proc", app_prep_start(env, opt["app"], get_dir(opt["ver"]), get_dir(opt["conf_to"]))
    ),
    "purge_mode_list": lambda mode_list: pu.purge_mode_list(deploy_context, mode_list),
    "purge_prefix_list": lambda prefix_list: pu.purge_prefix_list(deploy_context, prefix_list),
    "run": lambda cwd, cmd: run(cmd, cwd=get_dir(cwd)),
    "remote_call": lambda steps: remote_call(env, deploy_context, steps),
    "start": lambda cwd, cmd: register("proc", start(start_log(env), script, cmd, cwd=get_dir(cwd))),
    "wait_all": lambda: wait_processes(registered("proc")),
    "wait_all_ok": lambda: wait_processes(registered("proc")) or never("failed"),
    "rsync": lambda fr, to: run(("rsync", "-acr", get_dir(fr)+"/", need_dir(get_dir(to))+"/")),
    "kube_report_make": lambda kube_context, out_path: changing_text(
        out_path, kr.get_cluster_report(cl.get_pods_json(cl.get_kubectl(kube_context), ()))
    ),
    "kube_report_serve": lambda name, subdir: kube_report_serve(script, get_dir(name), subdir),
    "notify_started": lambda opt: register(
        "notify_succeeded", ny.notify_started(
            script([["notify_wait_finish"]]),
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
    "distribution_run": lambda opt: distribution.distribution_run(
        opt["groups"], loads(read_text(get_dir(opt["tasks"]))), opt["try_count"], opt["check_task"], opt["check_period"],
        lambda arg: start(
            start_log(env), script, arg_substitute(arg, opt["command"]), cwd=get_dir(arg_substitute(arg, opt["dir"]))
        )
    ),
    "secret_get": lambda fn, k8s_path: changing_text(get_dir(fn), access(deploy_context, k8s_path)),
    "write_lines": lambda fn, lines: changing_text(get_dir(fn), "\n".join(lines)),
}


def main():
    env, script_path, op = (os.environ, *sys.argv)
    ctx = {}
    steps = loads(op)
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
    script = (lambda arg: (*py_cmd(), script_path, dumps(arg)))
    handlers = get_step_handlers(script, env, env["C4DEPLOY_CONTEXT"], get_dir, register, registered)
    for step in steps:
        if need_plan:
            log(dumps(step))
        handlers[step[0]](*step[1:])
    log("OK")
