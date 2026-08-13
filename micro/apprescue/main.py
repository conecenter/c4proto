#!/usr/bin/env python3
from subprocess import check_output
from json import loads
from os import environ
from time import sleep
from traceback import print_exc
from sys import stderr

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None
def log(msg): print(msg, file=stderr, flush=True)

def process_target(kubeconfig, target, was_failure):
    kc = ("kubectl", "--kubeconfig", kubeconfig, "--context", target["kube_context"])
    mon = target["mon"]
    app = target["app"]
    logs = check_output([*kc, "logs", f"deploy/{mon}", "--since=1m"], timeout=15).decode()
    log(f"processing {app}")
    if "refresh-ok" in logs: return False
    if not was_failure: return True
    log(f"refresh-fail without refresh-ok in last 2m for {app}, checking pods")
    pods = loads(check_output([*kc, "get", "pods", "-l", f"app={app}", "-o", "json"], timeout=10))["items"]
    ready_pod_times = sorted([
        (sel(cs, "state", "running", "startedAt"), pod["metadata"]["name"])
        for pod in pods
        for cs in [one_opt(sel(pod,"status", "containerStatuses"))] if sel(cs, "ready")
    ])
    log(f"ready: {len(ready_pod_times)}")
    if len(ready_pod_times) < 2: return was_failure
    master_pod_name = ready_pod_times[0][1]
    log(f"deleting {master_pod_name}")
    check_output([*kc, "delete", "pod", master_pod_name], timeout=20)
    return True

def main():
    kubeconfig = environ["C4KUBECONFIG"]
    # C4RESCUE_TARGETS: [{"kube_context": "...", "mon": "...-umon4def-main", "app": "...-def-main"}, ...]
    targets = loads(environ["C4RESCUE_TARGETS"])
    mut_failures = set()
    while True:
        for target in targets:
            try:
                app = target["app"]
                if process_target(kubeconfig, target, app in mut_failures): mut_failures.add(app)
                elif app in mut_failures: mut_failures.remove(app)
            except Exception: print_exc()
        sleep(60)

if __name__ == "__main__": main()
