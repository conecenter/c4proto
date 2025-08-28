
from typing import NamedTuple
from queue import Queue
from sys import stderr
from os import environ
from json import loads, decoder as json_decoder
from time import sleep, gmtime, strftime
from tempfile import TemporaryDirectory
from logging import exception, info, basicConfig, DEBUG, INFO

from . import list_dir, repeat, read_text, one, group_map, decode
from .git import git_pull, git_clone
from .cmd import get_cmd
from .threads import TaskQ, daemon, TaskFin
from .servers import http_serve, tcp_serve
from .cluster import get_kubectl, get_secret_part, init_kafka_producer
from .cio_preproc import plan_steps, arg_substitute
from .cio_client import log_addr, cmd_addr, reporting_addr, task_hint, kafka_addr, log_topic
from .cio import run_steps
from .reporting import init_reporting

class ReportReq(NamedTuple): pass

class LogLine(NamedTuple):
    data: bytes

class LogFin(NamedTuple): pass

class CronCheck(NamedTuple): pass

class PostReq(NamedTuple):
    data: bytes

class PlainTask(NamedTuple):
    key: str
    value: str
    skip: str | None
    cmd: list

def select_def(def_list, s0, s1): return [d[2] for d in def_list if d[0] == s0 and d[1] == s1]

def load_no_die(s, path):
    try: return loads(s)
    except json_decoder.JSONDecodeError as e: exception(f"error parsing {path}", e)

def load_def_list(d):
    return [d for p in list_dir(d) if p.endswith(".json") for c in [load_no_die(read_text(p), p)] if c for d in c]

def fallback(fb, f, *args):
    try: return f(*args)
    except Exception as e:
        exception(e)
        return fb

def get_service_steps(def_list):
    services = [one(*d[1:]) for d in def_list if d and d[0] == "service"]
    return [[["queue","name",s],["queue","hint",task_hint(s)],["queue","skip",s],["call",{"op":s}]] for s in services]

def get_tm_abbr():
    tm = gmtime()
    return "ETKNRLP"[tm.tm_wday], strftime("%H:%M", tm)

def get_cron_steps(main_def_list, tm_abbr):
    info(f"at {tm_abbr}")
    return [[["call", act]] for act in [
        *select_def(main_def_list, "weekly", tm_abbr[0]+tm_abbr[1]),
        *select_def(main_def_list, "daily", tm_abbr[1])
    ]]

def steps_to_task(env, def_list, steps):
    steps = plan_steps((steps, (def_list, None)))
    steps = arg_substitute({"group":"{group}","task":"{task}"}, steps, die_on_undef=True)
    cmd = get_cmd(run_steps, env, steps)
    opt = {k:one(*{*vs}) for k, vs in group_map([d[1:] for d in steps if d[0] == "queue"], lambda d: d).items()}
    return PlainTask(opt.get("name", "def"), opt.get("hint", task_hint("script")), opt.get("skip"), cmd)

def get_pull_task(def_repo_dir):
    return PlainTask("pull", task_hint("pull"), "pull", get_cmd(git_pull, def_repo_dir))

def tasks_push_skip(tasks, task):
    return [*[t for t in tasks if task.skip is None or task.skip != t.skip], task]

def main():
    env = environ
    basicConfig(level=INFO)
    msg_q = Queue()
    task_q = TaskQ(msg_q, log_addr())
    daemon(tcp_serve, log_addr(), lambda b: (msg_q.put(LogLine(b)), logger_send(b)), lambda: msg_q.put(LogFin()))
    daemon(http_serve, cmd_addr(), {"/c4q": lambda d: msg_q.put(PostReq(d))})
    daemon(repeat, lambda: (msg_q.put(CronCheck()), sleep(30)))
    reporting_to_start, report_send = init_reporting(reporting_addr(), lambda: msg_q.put(ReportReq()))
    logger_to_start, logger_send = init_kafka_producer(kafka_addr(0), log_topic())
    for f in [*reporting_to_start, logger_to_start]: daemon(f)
    dir_life = TemporaryDirectory()
    def_repo_dir = f"{dir_life.name}/def_repo"
    repo = decode(get_secret_part(get_kubectl(env["C4DEPLOY_CONTEXT"]), env["C4CRON_REPO"]))
    git_clone(repo, env["C4CRON_BRANCH"], def_repo_dir)
    report = lambda: {"active": {**task_q.active}, "pending": [[t.key, t.value] for t in tasks]}
    tasks, requested_steps, tm_abbr = (), (), ()
    while True:
        try: # here we try to change state atom-ly
            tasks, requested_steps, tm_abbr, reschedule = handle_any(
                env, def_repo_dir, report, report_send, tasks, requested_steps, tm_abbr, task_q.get()
            )
            while reschedule:
                task = next((t for t in tasks if not task_q.can_not_submit(t.key)), None)
                if task is None: break
                tasks = [t for t in tasks if t is not task]
                task_q.submit(task.key, task.value)(task.cmd)
        except Exception as e:
            exception(e)

def handle_any(env, def_repo_dir, report, report_send, tasks, requested_steps, tm_abbr, msg):
    reschedule = False
    match msg:
        case LogLine(bs): stderr.write(decode(bs) + "\n")
        case LogFin():
            pass #stderr.write("FIN\n")
        case PostReq(data):
            tasks = tasks_push_skip(tasks, get_pull_task(def_repo_dir))
            requested_steps = (*requested_steps, loads(decode(data)))
            reschedule = True
        case CronCheck():
            tasks = tasks_push_skip(tasks, get_pull_task(def_repo_dir))
            reschedule = True
        case TaskFin(ok, key, _):
            if ok and key == "pull":
                main_dir, util_dir = env["C4CRON_MAIN_DIR"], env["C4CRON_UTIL_DIR"]
                def_lists = {k: load_def_list(f'{def_repo_dir}/{k}') for k in sorted({main_dir, util_dir})}
                service_steps = fallback((), get_service_steps, def_lists[main_dir])
                tm_abbr, was_tm_abbr = get_tm_abbr(), tm_abbr
                same_tm = was_tm_abbr == tm_abbr
                cron_steps = () if same_tm else fallback((), get_cron_steps, def_lists[main_dir], tm_abbr)
                for ss in [*requested_steps,*cron_steps,*service_steps]:
                    task = fallback(None, steps_to_task, env, def_lists[util_dir], ss)
                    if task is not None: tasks = tasks_push_skip(tasks, task)
                requested_steps = ()
            reschedule = True
        case ReportReq(): report_send(report())
    return tasks, requested_steps, tm_abbr, reschedule
