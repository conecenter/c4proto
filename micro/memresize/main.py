from sys import stderr
from os import environ
from re import findall, search
from math import ceil
from subprocess import run, check_output
from json import loads, dumps
from time import sleep
from traceback import print_exc

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def log(message: str) -> None: print(message, file=stderr, flush=True)

def env_float(key: str, default: float) -> float:
    try: return float(environ.get(key, default))
    except ValueError as e: raise Exception(f"bad value of {key}") from e

def never(v): raise Exception(v)

def need_one_str(v): return True if v == "1" else False if v is None else never("argument must be '1' or missing")

def to_mib_opt(q: str) -> float:
    m = search(r"^([\d\.]+)\s*(Ki|Mi|Gi)$", q or "")
    return [float(m[1]) * {"Ki":1/1024,"Mi":1,"Gi":1024}[m[2]]] if m else ()

def main() -> None:
    pattern = environ.get("C4MEM_POD_LIKE", "^de-")
    scale_up_trigger = env_float("C4MEM_SCALE_UP_TRIGGER", 1.2)
    scale_down_trigger = env_float("C4MEM_SCALE_DOWN_TRIGGER", 0.5)
    dry_run = need_one_str(environ.get("C4MEM_DRY_RUN"))
    sleep_for = env_float("C4MEM_SLEEP_FOR_SECONDS", 120.0)
    kc = ("kubectl", "--kubeconfig", environ["C4KUBECONFIG"], "--context", environ["C4MEM_KUBE_CONTEXT"])
    api_path = f'/apis/metrics.k8s.io/v1beta1/namespaces/{environ["C4MEM_KUBE_NS"]}/pods'
    def process_once() -> None:
        usage_by_pod = {
            pod_metrics["metadata"]["name"]: sel(cm, "usage", "memory")
            for pod_metrics in loads(check_output([*kc, "get", "--raw", api_path]))["items"]
            for cm in sel(pod_metrics, "containers") or [] if cm["name"] == "main"
        }
        tasks = [
            (name, max(1, ceil(usage_mib)))
            for pod in loads(check_output([*kc, "get", "pods", "-o", "json"]))["items"]
            for name in [pod["metadata"]["name"]] if search(pattern, name)
            for usage_mib in to_mib_opt(usage_by_pod.get(name))
            for c in sel(pod, "spec", "containers") or [] if c["name"] == "main"
            for req_mib in to_mib_opt(sel(c, "resources", "requests", "memory"))
            for ratio in [usage_mib / req_mib] if ratio >= scale_up_trigger or ratio <= scale_down_trigger
        ]
        for name, target_mib in tasks:
            payload = {"spec":{"containers":[{"name":"main","resources":{"requests":{"memory":f"{target_mib}Mi"}}}]}}
            cmd = [*kc, "patch", "pod", name, "--subresource", "resize", "-p", dumps(payload, sort_keys=True)]
            log(f"going to run: {' '.join(cmd)}")
            if dry_run: log("dry-run")
            else: log("ok" if run(cmd, check=False).returncode == 0 else "failed")
    while True:
        try: process_once()
        except Exception: print_exc()
        sleep(sleep_for)

if __name__ == "__main__": main()

# If you want me to start from this ultra-compact, comprehension-heavy style next time, spell that out up front. For example: “please
# produce a ~60-line script in the terse style I pasted (single-file, heavy on comprehensions, no optional features, just target
# the main container).” Providing a short snippet as the style reference and calling out constraints (e.g., “no comments, only Mi/Gi
# handling, use --subresource resize”) will let me align the first draft with the patterns you prefer.
