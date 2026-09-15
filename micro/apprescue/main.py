#!/usr/bin/env python3
from subprocess import check_output, run
from json import loads
from os import environ
from time import monotonic, sleep
from traceback import print_exc
from sys import stderr

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None
def log(msg): print(msg, file=stderr, flush=True)

def precapture(kc, precapture_cmd, master_pod_name):
    # fire&forget: снимаем состояние будущего master (тот под, что и рестартнём).
    # apprescue знает только путь до хука, ничего про jcmd/JFR.
    # зависший exec обрезает timeout-бинарь check=False глотает код возврата;
    # ошибки exec ловит внешний try на вызове — здесь перехватывать нечего.
    r = run(["timeout", "25", *kc, "exec", master_pod_name, "--", precapture_cmd], check=False)
    log(f"precapture on {master_pod_name} rc={r.returncode}")

def process_target(kubeconfig, precapture_cmd, target, was_failure, last_rescue_at):
    kc = ("kubectl", "--kubeconfig", kubeconfig, "--context", target["kube_context"])
    mon = target["mon"]
    app = target["app"]

    if monotonic() - last_rescue_at < 300:
        log(f"{app}: rescue cooldown")
        return was_failure, last_rescue_at

    logs = check_output([*kc, "logs", f"deploy/{mon}", "--since=2m"], timeout=15).decode()
    ok_count = logs.count("refresh-ok")
    log(f"processing {app}: {ok_count} refresh-ok in last 2m")

    if ok_count >= 4: return False, last_rescue_at
    if ok_count == 3: return was_failure, last_rescue_at
    log(f"{app}: unhealthy, checking pods") # ok_count < 3 — нездоров

    pods = loads(check_output([*kc, "get", "pods", "-l", f"app={app}", "-o", "json"], timeout=10))["items"]
    ready_pod_times = sorted([
        (sel(cs, "state", "running", "startedAt"), pod["metadata"]["name"])
        for pod in pods
        for cs in [one_opt(sel(pod, "status", "containerStatuses"))] if sel(cs, "ready")
    ])
    master_pod_name = ready_pod_times[0][1] if ready_pod_times else None

    if not was_failure:
        # первый страйк: рестарта ещё не будет — снимем состояние будущего master заранее
        if master_pod_name: precapture(kc, precapture_cmd, master_pod_name)
        return True, last_rescue_at

    log(f"{app}: unhealthy, ready: {len(ready_pod_times)}")
    if len(ready_pod_times) < 2: return True, last_rescue_at

    log(f"deleting {master_pod_name}")
    check_output([*kc, "delete", "pod", master_pod_name], timeout=20)
    return False, monotonic()  # оптимистично: под убили — считаем вылеченным; повторный рескью придавит cooldown

def main():
    kubeconfig = environ["C4KUBECONFIG"]
    precapture_cmd = environ["C4RESCUE_PRECAPTURE"]
    # C4RESCUE_TARGETS: [{"kube_context": "...", "mon": "...-umon4def-main", "app": "...-def-main"}, ...]
    targets = loads(environ["C4RESCUE_TARGETS"])
    mut_failures = set()
    mut_last_rescue = {}

    while True:
        for target in targets:
            try:
                app = target["app"]
                failed, last_rescue_at = process_target(
                    kubeconfig, precapture_cmd, target, app in mut_failures, mut_last_rescue.get(app, float("-inf")),
                )
                if failed: mut_failures.add(app)
                else: mut_failures.discard(app)
                mut_last_rescue[app] = last_rescue_at
            except Exception: print_exc()
        sleep(60)

if __name__ == "__main__": main()
