
from typing import NamedTuple
from queue import Queue
from sys import stderr
from os import environ
from json import loads, dumps, decoder as json_decoder
from time import sleep, gmtime, strftime
from tempfile import TemporaryDirectory
from functools import reduce

from . import never, path_exists, list_dir, log, repeat, read_text, one, group_map, decode
from .git import git_pull, git_clone
from .cmd import get_cmd
from .threads import TaskQ, daemon, TaskFin
from .servers import http_serve, tcp_serve
from .cluster import get_kubectl, get_secret_part
from .cio_preproc import plan_steps
from .cio_client import log_addr, cmd_addr, task_kv
from .cio import run_steps

###

class CronCheck(NamedTuple): pass

class PostReq(NamedTuple):
    data: bytes

def select_def(def_list, s0, s1): return [d[2] for d in def_list if d[0] == s0 and d[1] == s1]

def load_no_die(s, path):
    try: return loads(s)
    except json_decoder.JSONDecodeError as e: log(f"error parsing {path}: {e}")

def load_def_list(d):
    return [d for p in list_dir(d) if p.endswith(".json") for c in [load_no_die(read_text(p), p)] if c for d in c]

def cron(main_def_list, last_tm_abbr):
    service_steps = [
        [["queue","name",d[1]],["queue","skip",d[1]],["call", d[1]]] for d in main_def_list if d and d[0] == "service"
    ]
    tm = gmtime()
    tm_abbr = ["ETKNRLP"[tm.tm_wday], strftime("%H:%M", tm)]
    same_tm = last_tm_abbr == tm_abbr
    same_tm or log(f"at {tm_abbr}")
    cron_steps = [] if same_tm else [[["call", act]] for act in [
        *select_def(main_def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
        *select_def(main_def_list, "daily", tm_abbr[1])
    ]]
    return tm_abbr, [*cron_steps, *service_steps]

class PlainTask(NamedTuple):
    key: str
    value: str
    skip: str | None
    cmd: list

def steps_to_task(env, report, def_list, steps):
    steps = plan_steps((steps, (def_list, None)))
    steps = [([*d,report()] if d[0] == "queue_report" else d) for d in steps]
    cmd = get_cmd(run_steps, env, steps)
    opt = {k:one(*{*vs}) for k, vs in group_map([d[1:] for d in steps if d[0] == "queue"], lambda d: d).items()}
    return PlainTask(opt.get("name", "def"), opt.get("hint", task_kv("script")[-1]), opt.get("skip"), cmd)

def get_pull_task(def_repo_dir):
    return PlainTask("pull", task_kv("pull")[-1], "pull", get_cmd(git_pull, def_repo_dir))

def tasks_push_skip(tasks, task):
    return [*[t for t in tasks if task.skip is None or task.skip != t.skip], task]

class MainState(NamedTuple):
    tasks: tuple[PlainTask] = ()
    requested_steps: tuple = ()
    tm_abbr: tuple[str] = ()

def main_inner(env):
    msg_q = Queue()
    task_q = TaskQ(msg_q, 2, log, log_addr())
    daemon(http_serve, cmd_addr(), {"/c4q": lambda d: msg_q.put(PostReq(d))})
    daemon(repeat, lambda: (msg_q.put(CronCheck()), sleep(30)))
    dir_life = TemporaryDirectory()
    def_repo_dir = f"{dir_life.name}/def_repo"
    repo = decode(get_secret_part(get_kubectl(env["C4DEPLOY_CONTEXT"]), env["C4CRON_REPO"]))
    git_clone(repo, env["C4CRON_BRANCH"], def_repo_dir)
    report = lambda: {"active":{*task_q.active}, "pending":{k: [t.value for t in q] for k, q in tasks.items()}}
    state = MainState()
    while True:
        try:
            match task_q.get():
                case PostReq(data):
                    state = state._replace(
                        tasks = tasks_push_skip(state.tasks, get_pull_task(def_repo_dir)),
                        requested_steps = (*requested_steps, loads(decode(data)))
                    )
                    # resched
                case CronCheck():
                    state = state._replace(tasks = tasks_push_skip(state.tasks, get_pull_task(def_repo_dir)))
                    # resched
                case TaskFin(ok, key, value):
                    if ok and key == "pull":
                        main_def_list = load_def_list(f'{def_repo_dir}/{env["C4CRON_MAIN_DIR"]}')
                        util_def_list = load_def_list(f'{def_repo_dir}/{env["C4CRON_UTIL_DIR"]}')
                        tm_abbr, cron_steps = cron(main_def_list, last_tm_abbr)
                        new_tasks = [steps_to_task(env, report, util_def_list, ss) for ss in [*requested_steps,*cron_steps]]
                        state = state._replace(
                            tasks = reduce(tasks_push_skip, new_tasks, state.tasks),
                            requested_steps = (),
                            tm_abbr = tm_abbr
                        )
                    # resched


            to_start = [tasks[k] for k in sorted(tasks.keys()) if tasks[k] and not task_q.can_not_submit(k)]
            for l_q in to_start:
                task = l_q.pop(0)
                task_q.submit(task.key, task.value)(task.cmd)
        except Exception as e: log(e)

###

class LogLine(NamedTuple):
    data: bytes
class LogFin(NamedTuple): pass

def main():
    msg_q = Queue()
    daemon(tcp_serve, log_addr(), lambda b: msg_q.put(LogLine(b)), lambda: msg_q.put(LogFin()))
    task_q = TaskQ(msg_q, 2, log, log_addr())
    task_q.submit(*task_kv("main"))(get_cmd(main_inner, environ))
    while True:
        match task_q.get():
            case LogLine(bs): stderr.write(decode(bs))
            case LogFin(): stderr.write("FIN\n")
            case TaskFin(_,_,_): never("main")
