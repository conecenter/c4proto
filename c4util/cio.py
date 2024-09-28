from queue import Empty, Queue
from random import random
import sys
from os import environ
import time
from time import sleep
from tempfile import TemporaryDirectory
from json import dumps, loads, decoder as json_decoder
from pathlib import Path
from datetime import datetime
from http.client import HTTPConnection
from functools import reduce
from re import match

from . import snapshots as sn, cluster as cl, git, kube_reporter as kr, notify as ny, distribution
from .cio_preproc import arg_substitute, plan_steps
from . import run, list_dir, log, Popen, wait_processes, changing_text, one, read_text, never_if, need_dir, \
    path_exists, read_json, http_exchange, http_check, never, debug_args, repeat, decode, run_no_die, group_map
from .cmd import get_cmd
from .threads import TaskQ, daemon, TaskFin, open_piped, empty_no_die
from .http_server import http_serve, PostReq

def app_prep_start(q, env, app, app_dir, up_path):
    changing_text(lock_path(up_path), "")
    q.submit(open_piped(get_cmd(app_prep, env, app, app_dir, up_path)), "", f'prep {up_path}')


def app_prep(env, app, app_dir, up_path):
    args = ("--context", app_dir, "--c4env", app, "--state", "main", "--info-out", up_path)
    cmd = ("python3", "-u", f'{env["C4CI_PROTO_DIR"]}/ci_prep.py', *args)
    run(cmd, env={**env, "KUBECONFIG": env["C4KUBECONFIG"]})
    Path(lock_path(up_path)).unlink()


def app_up(it):
    prune_list = [f"--prune-whitelist={v}" for v in (
        "/v1/Service", "apps/v1/Deployment", "apps/v1/StatefulSet",
        "networking.k8s.io/v1/Ingress", "extensions/v1beta1/Ingress"
    )]
    cmd = (*cl.get_kubectl(it["kube-context"]), "apply", "--prune", *prune_list, "-l", f'c4env={it["c4env"]}', "-f-")
    run(cmd, text=True, input="\n".join(dumps(v) for v in it["manifests"]))


def lock_path(path): return f"{path}.proc"


def app_cold_start(q, env, conf_from, snapshot_from):
    q.submit(open_piped(get_cmd(app_cold_start_blocking, env, conf_from, snapshot_from)), "", f'cold {conf_from}')


def app_cold_start_blocking(env, conf_from, snapshot_from):
    repeat(lambda: (log("..."), sleep(1)) if path_exists(lock_path(conf_from)) else True, (True,))
    it = read_json(conf_from)
    never_if(f'bad ctx {it["kube-context"]} {it["c4env"]}' if it["kube-context"] != env["C4DEPLOY_CONTEXT"] else None)
    pod_templates = [man["spec"]["template"] for man in it["manifests"] if man["kind"] == "Deployment"]
    install_prefix = one(*sn.get_prefixes_from_pods(pod_templates))
    sn.snapshot_copy(env, snapshot_from, {"prefix": install_prefix})
    app_up(it)


def wait_no_app(kube_context, app):
    repeat(lambda: sleep(2) if len(sn.get_app_pods(cl.get_kubectl(kube_context), app)) > 0 else True, (True,))


def app_stop_start(kube_context, app):
    return Popen((*cl.get_kubectl(kube_context), "delete", "service,deploy,statefulset,ingress", "-l", f'c4env={app}'))


def app_substitute(fr, sub, to): changing_text(to, reduce(lambda t, s: t.replace(*s), sub, read_text(fr)))


def select_def(def_list, s0, s1): return [d[2] for d in def_list if d[0] == s0 and d[1] == s1]


def load_no_die(s, path):
    try:
        return loads(s)
    except json_decoder.JSONDecodeError as e:
        log(f"error parsing {path}: {e}")


def load_def_list(a_dir):
    return [d for p in list_dir(a_dir) if p.endswith(".json") for c in [load_no_die(read_text(p), p)] if c for d in c]


def log_message(env, log_path): return f'to view log: kcd exec -it {env["HOSTNAME"]} -- tail -f {log_path} -n 1000'


def access(kube_context, k8s_path): return decode(cl.get_secret_part(cl.get_kubectl(kube_context), k8s_path))


def rsync_local(fr, to): run(("rsync", "-acr", fr+"/", need_dir(to)+"/"))


def make_task_q(env, to_title) -> TaskQ: return TaskQ(
    min_exec_time = 2,
    get_log_path = lambda: f"/tmp/c4log-{datetime.now().isoformat().replace(':','-').split('.')[0]}-{str(random()).split('.')[-1]}",
    log_starting = lambda proc, log_path: (debug_args("starting", proc.args), log(log_message(env, log_path))),
    log_finished = lambda msg: log(
        f'{"succeeded" if msg.ok else "failed"} {to_title(msg.key, msg.value)}, {log_message(env, msg.log_path)}'
    ),
    log_progress = lambda count: log(f'{count} tasks in progress'),
)


def distribution_run_outer(env, groups, tasks, try_count, check_task, dir_te, command_te):
    q = make_task_q(env, lambda group, task: f"group {group} task {task}")
    def do_start(group, task):
        arg = {"group": group, "task": task}
        proc = open_piped(arg_substitute(arg, command_te), cwd=arg_substitute(arg, dir_te))
        never_if(None if len(group) > 0 else "bad group")
        q.submit(proc, group, task)
    def do_get():
        m = q.get()
        return m.ok, m.key, m.value
    distribution.distribution_run(groups, tasks, try_count, check_task, do_start, do_get)


def cron_serve(env, def_repo_dir):
    last_tm_abbr = ""
    while True:
        tm = time.gmtime()
        tm_abbr = ("ETKNRLP"[tm.tm_wday], time.strftime("%H:%M", tm))
        if last_tm_abbr == tm_abbr:
            continue
        last_tm_abbr = tm_abbr
        need_def_repo(env, def_repo_dir)
        def_list = load_def_list(f'{def_repo_dir}/{env["C4CRON_MAIN_DIR"]}')
        log(f"at {tm_abbr}")
        acts = [
            *select_def(def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
            *select_def(def_list, "daily", tm_abbr[1])
        ]
        for act in acts:
            send_wish([["call", act]])
        for d in def_list:
            if d and d[0] == "service":
                send_wish([["queue","name",d[1]],["queue","skip","service"],["call", d[1]]])
        time.sleep(30)

def need_def_repo(env, a_dir):
    if path_exists(a_dir): git.git_pull(a_dir)
    else: git.git_clone(access(env["C4DEPLOY_CONTEXT"], env["C4CRON_REPO"]), env["C4CRON_BRANCH"], a_dir)

# todo send success/failure ["queue","name",...], ["queue","prepend",1], ["queue","skip","done"]}]

def main_serve(env):
    task_q: TaskQ = make_task_q(env, lambda task_key, life: read_text(f'{life.name}/req.json'))
    daemon(http_serve, task_q.q, get_cmd_addr())
    daemon(repeat, lambda: (task_q.q.put(PostReq("/c4q", dumps([["queue","name","cron"],["queue","skip","service"],["cron"]]).encode("utf-8"))), sleep(5)))
    dir_life = TemporaryDirectory()
    tasks = {}
    def handle_command():
        msg = task_q.get()
        start_next(msg.key) if isinstance(msg, TaskFin) else handle_post(msg) if isinstance(msg, PostReq) else never(msg)
    one_or_def = lambda items, default: one(*{*items}) if items else default
    q_opt = lambda steps, k, default: one_or_def(select_def(steps, "queue", k), default)
    def handle_post(msg: PostReq):
        never_if(msg.path != "/c4q")
        def_repo_dir = f"{dir_life.name}/def_repo"
        need_def_repo(env, def_repo_dir)
        steps = loads(msg.data.decode("utf-8"))
        steps = plan_steps((steps, (load_def_list(f'{def_repo_dir}/{env["C4CRON_UTIL_DIR"]}'), None)))
        task_key = q_opt(steps, "name", "def")
        skip = q_opt(steps, "skip", None)
        was_q = tasks.get(task_key, [])
        kept_q = was_q if skip is None else [p_steps for p_steps in was_q if skip != q_opt(p_steps, "skip", None)]
        tasks[task_key] = [steps, *kept_q] if q_opt(steps, "prepend", None) else [*kept_q, steps]
        start_next(task_key)


        # no fail post
        l_q = tasks.get(task_key, [])
        if l_q and not task_q.can_not_submit(task_key)
        #l_q.pop()
        log_hints = [one(d[1:]) for d in steps if d[0] == "log_hint"]

            life = TemporaryDirectory()
            #

            rsync_local(dir_life.name, life.name)
            #
            log_path = task_q.get_log_path()
            changing_text(f'{life.name}/log_path', log_path)
            changing_text(f'{life.name}/req.json', msg.text)
            proc = open_piped(get_cmd(run_steps, env, [step], life.name))
            task_q.submit(proc, service_name, life, log_path=log_path)
            o = {"message":f'starting, {log_message(env, log_path)}', "command": ["tail", "-f", log_path, "-n", "1000"]}
            msg.respond_text(200, dumps(o))


    repeat(handle_command)


#.decode("utf-8")

def read_text_once(path):
    res = read_text(path)
    Path(path).unlink()
    return res


def kafka_client_serve(deploy_context, port_offset, conf):
    life = TemporaryDirectory()
    kc = cl.get_kubectl(deploy_context)
    handlers = {
        "is": lambda path, k, v: [("L",k,v)],
        "is_content_of": lambda path, k, v: [("L",k,decode(cl.get_secret_part(kc,v)))],
        "is_path_of": lambda path, k, v: [("L",k,path),("F",path,cl.get_secret_part(kc,v))]
    }
    todo = [r for i, (k, mode, v) in enumerate(conf) for r in handlers[mode](f"{life.name}/{i}",k,v)]
    for c, k, v in todo: c == "F" and Path(k).write_bytes(v)
    conf_path = f"{life.name}/kafka.conf"
    changing_text(conf_path, "".join(f"{k}={v}\n" for c, k, v in todo if c == "L"))
    cp = read_text("/c4/kafka-clients-classpath").strip()
    src_path = str(Path(__file__).parent/"kafka.java")
    run(("java", "--source", "21", "--enable-preview", "-cp", cp, src_path, str(cl.kafka_port(port_offset)), conf_path))


def purge(env, prefix, clients):
    kube_context = env["C4DEPLOY_CONTEXT"]
    kc = cl.get_kubectl(kube_context)
    cl.wait_no_active_prefix(kc, prefix)
    mc = cl.s3init(kc)
    ls = lambda tp: cl.s3list(mc, cl.s3path(f"{prefix}{tp}"))
    run_no_die((*mc, "rm", *ls(".snapshots"), *ls(".txr")))
    for id in clients:
        cl.kafka_post(id, "rm", prefix)
    never_if(ls(".snapshots"))


def secret_part_as_file(secret, file_name, to_dir):
    to_path = f"{to_dir}/{file_name}"
    Path(to_path).write_bytes(secret(file_name))
    return to_path


def kube_report_serve(d, subdir_pf):
    life = TemporaryDirectory()
    wait_processes([Popen(
        get_cmd(kube_report_make, kube_context, f"{life.name}/{kube_context}.pods.txt")
    ) for kube_context in cl.get_all_contexts()])
    git.git_pull(d)
    rsync_local(life.name, f"{d}/{subdir_pf}")
    git.git_save_changed(d)
    sleep(30)


def kube_report_make(kube_context, out_path):
    changing_text(out_path, kr.get_cluster_report(cl.get_pods_json(cl.get_kubectl(kube_context), ())))


def local_kill_serve():
    stats = [f"{p}/status" for p in list_dir("/proc") if p.split("/")[-1].isdigit()]
    to_kill = sorted(int(p.split("/")[-2]) for p in stats if path_exists(p) and "\nPPid:\t1\n" in read_text(p))[1:]
    run(("kill", *[str(p) for p in to_kill])) if len(to_kill) > 0 else sleep(5)


def access_once(deploy_context, d): return access(deploy_context, read_text_once(f"{d}/.c4k8s_path"))


def get_step_handlers(env, deploy_context, get_dir, main_q: TaskQ): return {
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
    "git_save_changed": lambda cwd: git.git_save_changed(get_dir(cwd)),
    "app_cold_start": lambda opt: app_cold_start(main_q, env, get_dir(opt["conf_from"]), opt["snapshot_from"]),
    "app_stop_start": lambda kube_context, app: app_stop_start(kube_context, app),
    "app_prep_start": lambda opt: app_prep_start(main_q, env, opt["app"], get_dir(opt["ver"]), get_dir(opt["conf_to"])),
    "app_substitute": lambda opt: app_substitute(get_dir(opt["conf_from"]), opt["substitute"], get_dir(opt["conf_to"])),
    "app_scale": lambda app, n: run((*cl.get_kubectl(deploy_context), "scale", "--replicas", str(n), "deploy", app)),
    "purge_start": lambda opt: main_q.submit(
        open_piped(get_cmd(purge, env, opt["prefix"], opt["clients"])), "", f'purge {opt["prefix"]}'
    ),
    "run": lambda cwd, cmd: run(cmd, cwd=get_dir(cwd)),
    "start": lambda cwd, cmd: main_q.submit(open_piped(cmd, cwd=get_dir(cwd)), "", cmd[0]),
    "wait_all": lambda: main_q.wait_all(False),
    "wait_all_ok": lambda: main_q.wait_all(True),
    "rsync": lambda fr, to: rsync_local(get_dir(fr), get_dir(to)),
    "kube_report_serve": lambda name, subdir: repeat(lambda: kube_report_serve(get_dir(name), subdir)),
    # "notify_started": lambda opt: ny.notify_started(get_dir, ny.notify_create_requests(
    #     access(deploy_context, opt["auth"]), opt["url"],
    #     time.time(), opt["work_hours"], opt["valid_hours"], log_message(env, read_text(get_dir("log_path")))
    # )),
    # "notify_succeeded": lambda: ny.notify_succeeded(get_dir),
    # todo replace "log_path"
    "distribution_run": lambda opt: distribution_run_outer(
        env, opt["groups"], loads(read_text(get_dir(opt["tasks"]))), opt["try_count"], opt["check_task"],
        get_dir(opt["dir"]), opt["command"]
    ),
    "secret_get": lambda fn, k8s_path: changing_text(get_dir(fn), access(deploy_context, k8s_path)),
    "write_lines": lambda fn, lines: changing_text(get_dir(fn), "\n".join(lines)),
    "wait_no_app": lambda kube_context, app: wait_no_app(kube_context, app),
    "local_kill_serve": lambda: repeat(local_kill_serve),
    "die_after": lambda per: daemon(lambda: (sleep(int(per[:-1]) * {"m":60, "h":3600}[per[-1]]), never("expired"))),
    "cron": lambda: cron_serve(env, get_dir("def_repo")),
    "kafka_client_serve": lambda opt: kafka_client_serve(deploy_context, opt["port_offset"], opt["conf"])
}


def run_steps(env, steps, tmp_dir):
    log("plan:\n" + "\n".join(f"\t{dumps(step)}" for step in steps))
    main_q = make_task_q(env, lambda key, title: title)
    handlers = get_step_handlers(env, env["C4DEPLOY_CONTEXT"], get_dir=(lambda nm: f'{tmp_dir}/{nm}'), main_q=main_q)
    for step in steps:
        log(dumps(step))
        handlers[step[0]](*step[1:])
    log("OK")


## above is used for "main" only

def get_cmd_addr(): return "127.0.0.1", 8000

def rand_id(): return str(random()).split('.')[-1]

def send_wish(steps):
    conn = HTTPConnection(*get_cmd_addr())
    http_check(*http_exchange(conn, "POST", f"/c4q", dumps(steps).encode("utf-8")))

# pod entrypoint -> cio ci_serve main -> cio ci_serve #-> cio main -> main_serve
# prod cio_call/snapshot_* -> ##-> cio ci_serve #-> cio main -> http-client -> http-server -> main_serve
#   main_serve -> run_steps
def main():
    steps = loads(one(*sys.argv[1:]))
    if steps == [["main"]]: return main_serve(environ)
    log_hint = rand_id()
    print(log_hint)
    send_wish([["log_hint",log_hint],*steps])





