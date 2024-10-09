
from time import sleep
from tempfile import TemporaryDirectory
from json import dumps, loads
from pathlib import Path
from functools import reduce
from queue import Queue
from logging import info, debug
from os import kill
from signal import SIGTERM
from subprocess import run as sp_run

from .threads import TaskQ, daemon
from .cio_client import post_json, task_kv, log_addr, localhost
from . import snapshots as sn, cluster as cl, git, kube_reporter as kr, distribution
from .cio_preproc import arg_substitute
from . import run, list_dir, Popen, wait_processes, changing_text, one, read_text, never_if, need_dir, \
    path_exists, read_json, never, repeat, decode, run_no_die, debug_args
from .cmd import get_cmd


def app_prep_start(q, env, app, app_dir, up_path):
    changing_text(lock_path(up_path), "")
    q.submit(*task_kv(f'prep {up_path}'))(get_cmd(app_prep, env, app, app_dir, up_path))


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
    q.submit(*task_kv(f'cold {conf_from}'))(get_cmd(app_cold_start_blocking, env, conf_from, snapshot_from))


def app_cold_start_blocking(env, conf_from, snapshot_from):
    repeat(lambda: (debug("..."), sleep(1)) if path_exists(lock_path(conf_from)) else True, (True,))
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


def rsync_local(fr, to): run(("rsync", "-acr", fr+"/", need_dir(to)+"/"))


def distribution_run_outer(groups, tasks, try_count, check_task, dir_te, command_te):
    task_q = TaskQ(Queue(), log_addr())
    def do_start(group, task):
        arg = {"group": group, "task": task}
        task_q.submit(group, task, min_exec_time=2)(arg_substitute(arg, command_te), cwd=arg_substitute(arg, dir_te))
    def do_get():
        m = task_q.get()
        return m.ok, m.key, m.value
    distribution.distribution_run(groups, tasks, try_count, check_task, do_start, do_get)


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
    def ls(tp):
        bucket = cl.s3path(f"{prefix}{tp}")
        proc = sp_run(debug_args("",(*mc, "ls", "--json", bucket)), check=False, text=True, capture_output=True)
        return [f'{bucket}/{loads(line)["key"]}' for line in proc.stdout.splitlines()] if proc.returncode == 0 else []
    run_no_die((*mc, "rm", *ls(".snapshots"), *ls(".txr")))
    for cl_id in clients:
        info(cl.kafka_post(cl_id, "rm", prefix))
    never_if(ls(".snapshots"))


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
    def path_text_contains_no_die(p, substr):
        try: return path_exists(p) and substr in read_text(p)
        except FileNotFoundError: return False
    to_kill = sorted(int(p.split("/")[-2]) for p in stats if path_text_contains_no_die(p, "\nPPid:\t1\n"))[1:]
    for p in to_kill:
        try:
            debug(f"will kill {p}")
            kill(p, SIGTERM)
        except ProcessLookupError: pass
    sleep(1 if to_kill else 5)


def access_once(deploy_context, d):
    path = f"{d}/.c4k8s_path"
    res = read_text(path)
    Path(path).unlink()
    return decode(cl.get_secret_part(cl.get_kubectl(deploy_context), res))


def get_step_handlers(env, deploy_context, get_dir, main_q: TaskQ): return {
    "#": lambda *args: None,
    "called": lambda *args: None,
    "queue": lambda *args: None,
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
    "purge_start": lambda opt: main_q.submit(*task_kv(f'purge {opt["prefix"]}'))(
        get_cmd(purge, env, opt["prefix"], opt["clients"])
    ),
    "run": lambda cwd, cmd: run(cmd, cwd=get_dir(cwd)),
    "start": lambda cwd, cmd: main_q.submit(*task_kv(cmd[0]))(cmd, cwd=get_dir(cwd)),
    "wait_all": lambda: main_q.wait_all(False),
    "wait_all_ok": lambda: main_q.wait_all(True),
    "rsync": lambda fr, to: rsync_local(get_dir(fr), get_dir(to)),
    "kube_report_serve": lambda name, subdir: repeat(lambda: kube_report_serve(get_dir(name), subdir)),
    "distribution_run": lambda opt: distribution_run_outer(
        opt["groups"], loads(read_text(get_dir(opt["tasks"]))), opt["try_count"], opt["check_task"],
        get_dir(opt["dir"]), opt["command"]
    ),
    "secret_get": lambda fn, k8s_path: changing_text(get_dir(fn), decode(
        cl.get_secret_part(cl.get_kubectl(deploy_context), k8s_path)
    )),
    "write_lines": lambda fn, lines: changing_text(get_dir(fn), "\n".join(lines)),
    "wait_no_app": lambda kube_context, app: wait_no_app(kube_context, app),
    "local_kill_serve": lambda: repeat(local_kill_serve),
    "die_after": lambda per: daemon(lambda: (sleep(int(per[:-1]) * {"m":60, "h":3600}[per[-1]]), never("expired"))),
    "kafka_client_serve": lambda opt: kafka_client_serve(deploy_context, opt["port_offset"], opt["conf"]),
    "queue_report": lambda opt, report: (
        info(dumps(report, indent=4, sort_keys=True)),
        opt and post_json((localhost(),opt["port"]), opt["path"], report)
    ),
}


def run_steps(env, steps):
    life = TemporaryDirectory()
    task_q = TaskQ(Queue(), log_addr())
    info("plan:\n" + "\n".join(f"\t{dumps(step)}" for step in steps))
    handlers = get_step_handlers(env, env["C4DEPLOY_CONTEXT"], get_dir=(lambda nm: f'{life.name}/{nm}'), main_q=task_q)
    for step in steps:
        info(dumps(step))
        handlers[step[0]](*step[1:])


# process tree part: pod entrypoint tini -> /main.py -> cio_server.py main -> cio.py run_steps
# prod cio_call/snapshot_* -> ##-> cio /ci_serve.py -> cio_client.py main -> http-client -> http-server
# inside server: http-server -> PostReq -> requested_steps -> tasks/PlainTask -> submit -> run_steps



# print(f"{str(int(monotonic()-started)).zfill(5)} {line}", end="", file=log_file, flush=True)