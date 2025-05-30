
from functools import partial
from json import loads
from os import environ
from subprocess import Popen, PIPE

from util import run_text_out, never

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def get_ci_serve(kube_context):
    kc = get_kc(kube_context)
    cio_name, = run_text_out((*kc, "get", "deploy", "-l", "c4cio", "-o", "name")).split()
    return *kc, "exec", cio_name, "--", "python3", "-u", "/ci_serve.py"

def init_cio_tasks(mut_cio_tasks, active_contexts):
    def get_cio_tasks():
        kube_contexts = [c["name"] for c in active_contexts]
        return [
            { "kube_context": kube_context, "status": status, "task_name": task_name, "queue_name": queue_name }
            for kube_context in kube_contexts
            for report in [loads(mut_cio_tasks.get(kube_context,"{}"))]
            for status, tasks in [
                ("active", sorted(report.get("active", {}).items())), ("pending", report.get("pending") or [])
            ]
            for queue_name, task_name in tasks
        ]
    def watcher(kube_context):
        with Popen((*get_ci_serve(kube_context), "reporting"), text=True, stdout=PIPE) as proc:
            for line in proc.stdout: mut_cio_tasks[kube_context] = line
    watchers = [partial(watcher, c["name"]) for c in active_contexts]
    return watchers, get_cio_tasks

def init_cio_logs(mut_cio_logs, active_contexts):
    def get_cio_logs():
        return []
    def watcher(kube_context):
        mut_cio_logs[kube_context] = { "all_lines": [], "key_lines": [] }
        with Popen((*get_ci_serve(kube_context), "consume_log"), text=True, stdout=PIPE) as proc:
            match proc.stdout.readline().split():
                case ["BEGINNING", b, "END", _]:
                    proc.stdin.write(f'{b}\n')
                case s: never(f'bad header: {s}')
            for line in proc.stdout:
                msg = loads(line)
                mut_cio_logs[kube_context]["all_lines"].append(msg)
                if len(msg) > 3: mut_cio_logs[kube_context]["key_lines"].append(msg)
    watchers = [partial(watcher, c["name"]) for c in active_contexts]
    return watchers, get_cio_logs