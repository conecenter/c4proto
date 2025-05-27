
from functools import partial
from json import loads
from os import environ
from subprocess import Popen, PIPE

from util import run_text_out

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context
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
    def cio_watcher(kube_context):
        kc = get_kc(kube_context)
        cio_name, = run_text_out((*kc, "get", "deploy", "-l", "c4cio", "-o", "name")).split()
        with Popen((*kc, "exec", cio_name, "--", "python3", "-u", "/ci_serve.py", "reporting"), text=True, stdout=PIPE) as proc:
            for line in proc.stdout: mut_cio_tasks[kube_context] = line
            proc.wait()
    cio_watchers = [partial(cio_watcher, c["name"]) for c in active_contexts]
    return cio_watchers, get_cio_tasks
