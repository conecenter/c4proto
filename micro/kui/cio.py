
from time import time_ns
from json import loads, dumps
from tempfile import TemporaryDirectory
from pathlib import Path
from subprocess import Popen, PIPE, check_output
from logging import info
from dataclasses import dataclass, replace

from util import rget, post, watch

@dataclass(frozen=True, kw_only=True)
class Search: # one row of search history; filters frozen at search time, dir holds the grep output
    dir: TemporaryDirectory       # auto-cleaned by GC once dropped from history
    query: str
    kube_context: str
    context_lines: int
    result_code: int = None       # None while still searching; 0 found, 1 not found, >1 error
    result_lines: int = None

def get_ci_serve(kc): return *kc, "exec", "-i", "svc/c4cio", "--", "python3", "-u", "/ci_serve.py"

def consumer_open(kc, op): return Popen((*get_ci_serve(kc), op), stdout=PIPE, stdin=PIPE)

def consumer_init(proc, hint):
    match proc.stdout.readline().split():
        case [b"BEGINNING", b, b"END", _]:
            proc.stdin.write(b + b'\n')
            proc.stdin.flush()
            offset = int(b)
            info(f"{hint}: {offset}")
        case s: raise Exception(f'bad header: {s}')

def init_cio_events(mut_cio_statuses, active_contexts, kcs):
    @rget("/cio_events")
    def load():
        return { "items": [
            { "kube_context": kube_context, "task": task, "status": status, "at": at }
            for kube_context, task, status, at in sorted((*k, *v) for k, v in mut_cio_statuses.items()) if status
        ]}
    @post("/cio_events.hide")
    def hide(kube_context, task):
        if mut_cio_statuses.get((kube_context, task)) is None: raise Exception(f"missing {kube_context} {task}")
        event = { "type": "task_status", "task": task, "status": "" }
        check_output((*get_ci_serve(kcs[kube_context]), dumps([["produce_event", event]])))
    def watcher(kube_context):
        with consumer_open(kcs[kube_context], "consume_events") as proc:
            consumer_init(proc, f"CIO EVENTS WATCH : {kube_context}")
            for line in proc.stdout:
                event = loads(line)
                if event["type"] == "task_status":
                    mut_cio_statuses[(kube_context,event["task"])] = (event["status"], event.get("at"))
    return load, hide, *(watch(watcher, c["name"]) for c in active_contexts)

def init_cio_tasks(mut_cio_tasks, mut_cio_proc_by_pid, active_contexts, kcs):
    @rget("/cio_tasks")
    def load(kube_context):
        if not kube_context: return { "need_filters": True, "managed_kube_contexts": managed_kube_contexts }
        report = loads(mut_cio_tasks.get(kube_context, b"{}"))
        pid_by_task = {
            (entry.get("queue"), entry.get("task")): pid
            for (ctx, pid), entry in mut_cio_proc_by_pid.items()
            if ctx == kube_context
        }
        items = [
            {
                "status": status,
                "queue_name": queue_name,
                "task_name": task_name,
                "pid": pid_by_task.get((queue_name, task_name)),
            }
            for status, tasks in [
                ("active", sorted((report.get("active") or {}).items())),
                ("pending", report.get("pending") or [])
            ]
            for queue_name, task_name in tasks
        ]
        return { "items": items, "managed_kube_contexts": managed_kube_contexts }
    @post("/cio_tasks.kill")
    def kill(kube_context, pid_str):
        pid = int(pid_str)
        if (kube_context, pid) not in mut_cio_proc_by_pid: raise Exception(f"no pid {pid} for {kube_context}")
        info(f"CIO TASK KILL {kube_context} pid={pid}")
        check_output((*kcs[kube_context], "exec", "svc/c4cio", "--", "kill", str(pid)))
    def watcher(kube_context):
        with Popen((*get_ci_serve(kcs[kube_context]), "reporting"), stdout=PIPE) as proc:
            for line in proc.stdout: mut_cio_tasks[kube_context] = line
    managed_kube_contexts = [c["name"] for c in active_contexts]
    return load, kill, *(watch(watcher, c["name"]) for c in active_contexts)

def init_cio_log_search(mut_log_paths, active_contexts, make_response):
    # search history per user: mut_searches[(mail, id)] -> Search, each isolated in its own dir.
    # id = str(time_ns): string key (round-trips via URL/JSON), explicitly sortable, and a useful column.
    # capped to CAP per user (dropped dirs GC'd).
    # TODO race: load iterates mut_searches while a concurrent search/prune mutates it (threads, no lock) ->
    #   rare "dict changed size during iteration"; same in kube_pods/cio_events/cio_tasks loads. fix via snapshot / whole-replace.
    mut_searches = {}
    CAP = 10
    def result_path(s): return f'{s.dir.name}/result'
    def prune(mail): # keep the newest CAP per user (by time id); dropped dirs clean up via GC
        mids = sorted((i for (m, i) in mut_searches if m == mail), key=int)
        for mid in mids[:-CAP]: del mut_searches[(mail, mid)]
    @rget("/cio_logs")
    def load(mail):
        return {
            "all_log_sizes": [
                { "kube_context": n, "log_size": p.stat().st_size if p.exists() else None }
                for c in active_contexts for n in [c["name"]] for p in [mut_log_paths.get(n)] if p
            ],
            "searches": [
                { "id": i, "query": s.query, "kube_context": s.kube_context,
                  "context_lines": s.context_lines, "result_code": s.result_code, "result_lines": s.result_lines }
                for i, s in sorted(((i, s) for (m, i), s in mut_searches.items() if m == mail), key=lambda t: int(t[0]), reverse=True) # newest first
            ],
        }
    @post("/cio_logs.search")
    def search(mail, kube_context, query, context_lines):
        base = mut_log_paths[kube_context] # unknown context -> KeyError = membership + anti-traversal guard
        cl = int(context_lines)
        sid = str(time_ns()) # time-based id: unique string key, sortable, and a useful column
        s = Search(dir=TemporaryDirectory(), query=query, kube_context=kube_context, context_lines=cl)
        mut_searches[(mail, sid)] = s
        prune(mail)
        context_arg = ("-C", str(cl)) if cl > 0 else ()
        with Path(result_path(s)).open(mode="wb") as f:
            with Popen(("grep", "-P", "-f-", base, *context_arg), stdin=PIPE, stdout=f) as proc:
                proc.communicate(query.encode())
        result_lines = int(check_output(("wc", "-l", result_path(s))).decode().split()[0])
        if (mail, sid) in mut_searches: # may have been pruned by a burst of newer searches
            mut_searches[(mail, sid)] = replace(s, result_code=proc.returncode, result_lines=result_lines)
    @post("/cio_logs.forget")
    def forget(mail, id): mut_searches.pop((mail, id), None) # on the loop; the dropped dir GC's
    @rget("/cio_logs.download")
    def download(mail, id, head, tail): # head/tail avoid loading huge results
        p = result_path(mut_searches[(mail, id)])
        return make_response(200, (), (
            check_output(("head", "-n", head, p)) if head else
            check_output(("tail", "-n", tail, p)) if tail else
            Path(p).read_bytes()
        ))
    return load, search, forget, download

def init_cio_log_watchers(mut_log_paths, mut_cio_proc_by_pid, active_contexts, kcs):
    def note_proc_event(kube_context, line):
        if not isinstance(line, list) or len(line) != 3: return
        event = line[-1]
        if not isinstance(event, dict): return
        pid = event.get("pid")
        if pid is None: return
        match event.get("event"):
            case "started": mut_cio_proc_by_pid[(kube_context, pid)] = event
            case "succeeded" | "failed": mut_cio_proc_by_pid.pop((kube_context, pid), None) # may be unpaired due to log truncation
    def watcher(kube_context):
        with consumer_open(kcs[kube_context], "consume_log") as proc:
            consumer_init(proc, f"CIO LOG WATCH : {kube_context}")
            dir_life = TemporaryDirectory()
            path = Path(f"{dir_life.name}/log")
            mut_log_paths[kube_context] = path
            with path.open(mode="wb") as f:
                for line in proc.stdout:
                    f.write(line)
                    f.flush()
                    try:
                        note_proc_event(kube_context, loads(line))
                    except Exception:
                        pass
    return [watch(watcher, c["name"]) for c in active_contexts]

# todo better: example query; hide args; one line filters?
