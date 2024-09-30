
from typing import NamedTuple
from queue import Queue
from sys import stderr
from os import environ
from json import loads, dumps, decoder as json_decoder
from time import sleep, gmtime, strftime
from tempfile import TemporaryDirectory

from . import never, path_exists, list_dir, log, repeat, read_text, one, group_map, decode
from .git import git_pull, git_clone
from .cmd import get_cmd
from .threads import TaskQ, daemon, TaskFin, open_piped, log_addr
from .servers import http_serve, tcp_serve
from .cluster import get_kubectl, get_secret_part
from .cio_preproc import plan_steps
from .cio_client import localhost, cmd_port, rand_hint
from .cio import task_kv, run_steps


###

class CronCheck(NamedTuple): pass
class PostReq(NamedTuple):
    data: bytes

def main_serve(env):
    def select_def(def_list, s0, s1): return [d[2] for d in def_list if d[0] == s0 and d[1] == s1]
    def load_no_die(s, path):
        try: return loads(s)
        except json_decoder.JSONDecodeError as e: log(f"error parsing {path}: {e}")
    def load_def_list(r_dir, subdir):
        if path_exists(r_dir): git_pull(r_dir)
        else:
            kc = get_kubectl(env["C4DEPLOY_CONTEXT"])
            git_clone(decode(get_secret_part(kc, env["C4CRON_REPO"])), env["C4CRON_BRANCH"], r_dir)
        files = list_dir(f"{r_dir}/{subdir}")
        return [d for p in files if p.endswith(".json") for c in [load_no_die(read_text(p), p)] if c for d in c]
    def cron(task_q, tasks, def_repo_dir, last_tm_abbr):
        def_list = load_def_list(def_repo_dir, env["C4CRON_MAIN_DIR"])
        tm = gmtime()
        tm_abbr = ["ETKNRLP"[tm.tm_wday], strftime("%H:%M", tm)]
        if last_tm_abbr != tm_abbr:
            last_tm_abbr[:] = tm_abbr
            log(f"at {tm_abbr}")
            schedule_steps_multi(task_q, tasks, def_repo_dir, [[["call", act]] for act in [
                *select_def(def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
                *select_def(def_list, "daily", tm_abbr[1])
            ]])
        schedule_steps_multi(task_q, tasks, def_repo_dir, [
            [["queue","name",d[1]],["queue","skip","service"],["call", d[1]]]
            for d in def_list if d and d[0] == "service"
        ])
    def runs():
        msg_q = Queue()
        task_q = TaskQ(msg_q, 2, log)
        daemon(http_serve, (localhost(), cmd_port()), {"/c4q": lambda d: msg_q.put(PostReq(d))})
        daemon(repeat, lambda: (msg_q.put(CronCheck()), sleep(30)))
        dir_life = TemporaryDirectory()
        def_repo_dir = f"{dir_life.name}/def_repo"
        tasks = {}
        last_tm_abbr = []
        while True:
            try: handle_any(task_q, tasks, def_repo_dir, last_tm_abbr, task_q.get())
            except Exception as e: log(e)
    def handle_any(task_q, tasks, def_repo_dir, last_tm_abbr, msg):
        match msg:
            case TaskFin(_, key, _): start_next(task_q, tasks, key)
            case PostReq(data): schedule_steps_multi(task_q, tasks, def_repo_dir, [loads(data.decode("utf-8"))])
            case CronCheck(): cron(task_q, tasks, def_repo_dir, last_tm_abbr)
            case other: never(other)
    def schedule_steps_multi(task_q, tasks, def_repo_dir, steps_list):
        def_list = load_def_list(def_repo_dir, env["C4CRON_UTIL_DIR"])
        for steps in steps_list: schedule_steps(task_q, tasks, def_list, steps)
    def schedule_steps(task_q, tasks, def_list, steps):
        steps = plan_steps((steps, (def_list, None)))
        steps = [([*d,report(task_q, tasks)] if d[0] == "queue_report" else d) for d in steps]
        opt = {k:one(*{*vs}) for k, vs in group_map([d[1:] for d in steps if d[0] == "queue"], lambda d: d).items()}
        q_name = opt.get("name", "def")
        skip = opt.get("skip")
        was_q = tasks.get(q_name, [])
        kept_q = was_q if skip is None else [p_steps for p_steps, p_skip, p_hint in was_q if skip != p_skip]
        tasks[q_name] = [*kept_q, (steps, skip, opt.get("hint", rand_hint()))]
        start_next(task_q, tasks, q_name)
    def start_next(task_q, tasks, q_name):
        l_q = tasks.get(q_name, [])
        if not l_q or task_q.can_not_submit(q_name): return
        steps, skip, hint = l_q.pop(0)
        task_q.submit(open_piped(get_cmd(run_steps, env, steps)), (q_name, hint))
    def report(task_q, tasks):
        res = {"active":{*task_q.active}, "pending":{k: [hint for steps, skip, hint in q] for k, q in tasks.items()}}
        log(dumps(res, indent=4, sort_keys=True))
        return res
    runs()

###

class LogLine(NamedTuple):
    data: bytes
class LogFin(NamedTuple): pass

def main():
    msg_q = Queue()
    daemon(tcp_serve, log_addr(), lambda b: msg_q.put(LogLine(b)), lambda: msg_q.put(LogFin()))
    task_q = TaskQ(msg_q, 2, log)
    task_q.submit(open_piped(get_cmd(main_serve, environ)),task_kv("main"))
    while True:
        match task_q.get():
            case LogLine(bs): stderr.write(bs.decode("utf-8"))
            case LogFin(): stderr.write("FIN\n")
            case TaskFin(_,_,_): never("main")
