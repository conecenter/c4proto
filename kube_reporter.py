from tempfile import TemporaryDirectory
import sys
import subprocess
import os
import pathlib
import time
import json


def debug_args(hint, args):
    print(hint+" "+" ".join(str(a) for a in args), file=sys.stderr)
    return args


def run(args, **opt): return subprocess.run(debug_args("running:", args), check=True, **opt)


def run_no_die(args, **opt): return subprocess.run(debug_args("running:", args), **opt).returncode == 0


def run_expiring(args, **opt):
    try:
        proc = subprocess.run(debug_args("running:", args), **opt)
        if proc.returncode == 0:
            return proc
    except subprocess.TimeoutExpired:
        pass


def single(items,get_default): return items[0] if len(items) == 1 else get_default()


def item_to_str(it):
    name = it.get("metadata", {}).get("name")
    if not name:
        return None
    spec = it.get("spec", {})
    node = "-".join(spec.get("nodeName", "").split("-")[:2])
    requests = single(spec.get("containers", []), lambda: {}).get("resources", {}).get("requests", {})
    req_str = "/".join(v for v in (requests.get("cpu", ""), requests.get("memory", "")) if v)
    container_status = single(it.get("status", {}).get("containerStatuses", []), lambda: {})
    image_pf = container_status.get("image", "").split(":")[-1]
    ready = container_status.get("ready")
    restart_count = str(container_status.get("restartCount", ""))
    started_at = container_status.get("state", {}).get("running", {}).get("startedAt", "")
    return name, image_pf, node, restart_count, started_at, "Y" if ready else "", req_str


def get_cluster_report(json_str):
    items = json.loads(json_str)["items"]
    head = ("NAME", "TAG", "NODE", "RESTARTS", "STARTED", "READY", "REQ")
    right = {"RESTARTS", "REQ"}
    rows = [head] + sorted(it for it in [item_to_str(it) for it in items] if it)
    widths = [max(len(str(row[cn])) for row in rows) for cn in range(len(head))]
    return "\n".join(" ".join(
        (row[cn].rjust if head[cn] in right else row[cn].ljust)(widths[cn], " ")
        for cn in range(len(head))
    ) for row in rows)


def serve():
    kube_config = os.environ["C4KUBECONFIG"]
    kube_contexts = os.environ["C4KUBE_CONTEXTS"].split(":")
    repo = pathlib.Path(os.environ["C4OUT_REPO"]).read_text(encoding='utf-8', errors='strict').strip()
    branch = os.environ["C4OUT_BRANCH"]
    out_dir = os.environ["C4OUT_DIR"]
    temp_dir = TemporaryDirectory()
    context = temp_dir.name
    if not run_no_die(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=context):
        run(("git", "init"), cwd=context)
        run(("git", "remote", "add", "origin", repo), cwd=context)
        run(("git", "checkout", "-b", branch), cwd=context)
    run(("git", "config", "user.email", "ci@c4proto"), cwd=context)
    run(("git", "config", "user.name", "ci@c4proto"), cwd=context)
    dir = f"{context}/{out_dir}"
    pathlib.Path(dir).mkdir(exist_ok=True)
    while True:
        for kube_context in kube_contexts:
            cmd = ("kubectl", "--kubeconfig", kube_config, "--context", kube_context, "get", "pods", "-o", "json")
            proc = run_expiring(cmd, text=True, capture_output=True, timeout=3)
            if proc:
                content = get_cluster_report(proc.stdout)
                out_path = f"{dir}/{kube_context}.pods.txt"
                pathlib.Path(out_path).write_text(content, encoding='utf-8', errors='strict')
        run(("git", "add", "."), cwd=context)
        if run_no_die(("git", "commit", "-m", "get pods"), cwd=context):
            run(("git", "push", "--set-upstream", "origin", branch), cwd=context)
        elif len(run(("git", "status", "--porcelain=v1"), cwd=context, text=True, capture_output=True).stdout.strip()) > 0:
            raise Exception("can not commit")
        else:
            print("unchanged")
        time.sleep(30)


serve()
