
from functools import partial
from json import loads
from os import environ
from subprocess import Popen, PIPE
import re

from util import run_text_out, never, log

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def get_ci_serve(kube_context):
    kc = get_kc(kube_context)
    cio_name, = run_text_out((*kc, "get", "deploy", "-l", "c4cio", "-o", "name")).split()
    return *kc, "exec", "-i", cio_name, "--", "python3", "-u", "/ci_serve.py"

def init_cio_tasks(mut_cio_tasks, active_contexts):
    def load(cio_kube_context='', **_):
        if not cio_kube_context: return { "need_filters": True }
        return { "items": [
            { "status": status, "task_name": task_name, "queue_name": queue_name }
            for report in [loads(mut_cio_tasks.get(cio_kube_context,"{}"))]
            for status, tasks in [
                ("active", sorted(report.get("active", {}).items())), ("pending", report.get("pending") or [])
            ]
            for queue_name, task_name in tasks
        ]}
    def watcher(kube_context):
        with Popen((*get_ci_serve(kube_context), "reporting"), text=True, stdout=PIPE) as proc:
            for line in proc.stdout: mut_cio_tasks[kube_context] = line
    watchers = [partial(watcher, c["name"]) for c in active_contexts]
    return watchers, { "cio_tasks.load": load }

def init_cio_logs(mut_cio_logs, active_contexts):
    def load(cio_kube_context='', cio_log_scope='', cio_log_task_exclude='', **_):
        if not cio_kube_context or cio_log_scope not in {"all_lines","key_lines"}:
            return { "need_filters": True }
        task_exclude_re = re.compile(cio_log_task_exclude) if cio_log_task_exclude else None
        logs = [
            { "offset": offset, "time": time, "task_name": task_name, **(
                { "args": args } if cio_log_scope == "all_lines" else
                { "content": args[0] } if len(args) == 1 else
                { "descr": args[0], "status": args[1], "args": args[2:] }
            ) }
            for offset, time, task_name, *args in sel(mut_cio_logs, cio_kube_context, cio_log_scope) or []
            if not task_exclude_re or not task_exclude_re.search(task_name)
        ][-20:]
        return { "items": logs }
    def watcher(kube_context):
        mut_cio_logs[kube_context] = { "all_lines": [], "key_lines": [] }
        with Popen((*get_ci_serve(kube_context), "consume_log"), text=True, stdout=PIPE, stdin=PIPE) as proc:
            match proc.stdout.readline().split():
                case ["BEGINNING", b, "END", _]:
                    proc.stdin.write(f'{b}\n')
                    proc.stdin.flush()
                    offset = int(b)
                case s: never(f'bad header: {s}')
            log(f"CIO LOG WATCH : {kube_context} {offset} : {" ".join((*get_ci_serve(kube_context), "consume_log"))}")
            for line in proc.stdout:
                msg = [offset, *loads(line)]
                offset += 1
                mut_cio_logs[kube_context]["all_lines"].append(msg)
                if len(msg) > 4: mut_cio_logs[kube_context]["key_lines"].append(msg)
    watchers = [partial(watcher, c["name"]) for c in active_contexts]
    return watchers, { "cio_logs.load": load }