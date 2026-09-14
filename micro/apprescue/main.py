#!/usr/bin/env python3
from subprocess import check_output
from json import loads
from os import environ
from time import monotonic, sleep
from traceback import print_exc
from sys import stderr

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None
def log(msg): print(msg, file=stderr, flush=True)

def process_target(kubeconfig, target, was_failure, last_rescue_at):
    kc = ("kubectl", "--kubeconfig", kubeconfig, "--context", target["kube_context"])
    mon = target["mon"]
    app = target["app"]

    logs = check_output([*kc, "logs", f"deploy/{mon}", "--since=2m"], timeout=15).decode()
    ok_count = logs.count("refresh-ok")
    log(f"processing {app}: {ok_count} refresh-ok in last 2m")

    if ok_count >= 4: return False, last_rescue_at
    if ok_count == 3: return was_failure, last_rescue_at
    if not was_failure: return True, last_rescue_at

    if monotonic() - last_rescue_at < 300:
        log(f"{app}: rescue cooldown")
        return True, last_rescue_at

    log(f"{app}: unhealthy, checking pods")
    pods = loads(check_output([*kc, "get", "pods", "-l", f"app={app}", "-o", "json"], timeout=10))["items"]
    ready_pod_times = sorted([
        (sel(cs, "state", "running", "startedAt"), pod["metadata"]["name"])
        for pod in pods
        for cs in [one_opt(sel(pod, "status", "containerStatuses"))] if sel(cs, "ready")
    ])
    log(f"ready: {len(ready_pod_times)}")
    if len(ready_pod_times) < 2: return True, last_rescue_at

    master_pod_name = ready_pod_times[0][1]
    log(f"deleting {master_pod_name}")
    check_output([*kc, "delete", "pod", master_pod_name], timeout=20)
    return True, monotonic()

def main():
    kubeconfig = environ["C4KUBECONFIG"]
    # C4RESCUE_TARGETS: [{"kube_context": "...", "mon": "...-umon4def-main", "app": "...-def-main"}, ...]
    targets = loads(environ["C4RESCUE_TARGETS"])
    mut_failures = set()
    mut_last_rescue = {}

    while True:
        for target in targets:
            try:
                app = target["app"]
                failed, last_rescue_at = process_target(
                    kubeconfig, target, app in mut_failures, mut_last_rescue.get(app, float("-inf")),
                )
                if failed: mut_failures.add(app)
                else: mut_failures.discard(app)
                mut_last_rescue[app] = last_rescue_at
            except Exception: print_exc()
        sleep(60)

if __name__ == "__main__": main()
