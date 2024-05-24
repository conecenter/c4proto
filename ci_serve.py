
import random
import subprocess
import json
import sys
import os
import time
import tempfile
import pathlib

import c4util.snapshots as sn
import c4util.purge as pu
from c4util import run, never, read_text, list_dir, log


def app_start(app_dir, app):
    out_dir_life = tempfile.TemporaryDirectory()
    info_path = f"{out_dir_life.name}/up.json"
    run(("c4ci_prep", "--context", app_dir, "--c4env", app, "--state", "main", "--info-out", info_path))
    run(("c4ci_up",), text=True, input=read_text(pathlib.Path(info_path)))


def app_stop(kube_context, app):
    info = {"c4env": app, "state": "c4-off", "kube-context": kube_context, "manifests": []}
    run(("c4ci_up",), text=True, input=json.dumps(info))


def op_dump(ctx):
    snapshot_list = ctx.get("snapshot_list")
    if snapshot_list:
        log("snapshot_list:")
        for l in snapshot_list:
            log(f"\t{l['lastModified']}\t{l['size']}\t{l['key']}")
    data = ctx.get("injection")
    if data:
        log(f"injection:\n{data}")
    return ctx


def clone_cron():
    return clone_repo("C4INJECTION_REPO", os.environ["C4CRON_BRANCH"])


def fire(script, dir_life, ev_abbr):
    subdir = os.environ["C4CRON_DIR"]
    pod_name = os.environ["HOSTNAME"]
    for path in list_dir(f"{dir_life.name}/{subdir}"):
        if path.endswith(".json"):
            for ev_abbr_list, steps in json.loads(read_text(path)):
                if ev_abbr in ev_abbr_list:
                    log_path = f"/tmp/c4log-{random.random()}"
                    log(f"starting task, to view log:\n\tkcd exec {pod_name} -- tail -f {log_path}", file=sys.stderr)
                    proc = subprocess.Popen(("python3", "-u", script, json.dumps(steps)))
                    subprocess.Popen(("sh", "-c", f"cat > {log_path}"), stdin=proc.stdout)


def main_operator(script):
    dir_life = clone_cron()
    last_tm_abbr = ""
    while True:
        tm = time.gmtime()
        tm_abbr = "ETKNRLP"[tm.tm_wday] + time.strftime("%H:%M", tm)
        if last_tm_abbr == tm_abbr:
            continue
        last_tm_abbr = tm_abbr
        run(("git", "pull"), cwd=dir_life.name)
        fire(script, dir_life, tm_abbr)
        time.sleep(30)


handlers = {
    "dump": op_dump,
    "snapshot_list": lambda ctx, kube_context, app: {**ctx, "snapshot_list": sn.snapshot_list(kube_context, app)},
    "snapshot_get": lambda ctx, arg_name: {**ctx, "snapshot": sn.snapshot_get(ctx["snapshot_list"], arg_name)},
    "snapshot_write": lambda ctx, dir_path: {**ctx, "": sn.snapshot_write(dir_path, *ctx["snapshot"])},
    "snapshot_read": lambda ctx, data_path_arg: {**ctx, "snapshot": sn.snapshot_read(data_path_arg)},
    "snapshot_put": lambda ctx, kube_context, app: {**ctx, "": sn.snapshot_put(*ctx["snapshot"], kube_context, app)},
    "injection_get": lambda ctx, branch, subdir: {**ctx, "injection": sn.injection_get(branch, subdir)},
    "injection_post":
        lambda ctx, kube_context, app: {**ctx, "": sn.injection_post(ctx["injection"], kube_context, app)},
    "injection_substitute": lambda ctx, fr, to: {**ctx, "injection": sn.injection_substitute(ctx["injection"], fr, to)},
    "injection_set": lambda ctx, value: {**ctx, "injection": value},
    "app_checkout": lambda ctx, branch: {**ctx, "app_dir_life": sn.clone_repo("C4APP_REPO", branch)},
    "app_start": lambda ctx, app: {**ctx, "": app_start(ctx["app_dir_life"].name, app)},
    "app_stop": lambda ctx, kube_context, app: {**ctx, "": app_stop(kube_context, app)},
    "purge_mode_list": lambda ctx, mode_list: {**ctx, "": pu.purge_mode_list(mode_list)},
    "purge_prefix_list": lambda ctx, mode_list: {**ctx, "": pu.purge_prefix_list(mode_list)},
    "clone_last_to_prefix_list":
        lambda ctx, fr, to_list: {**ctx, "": sn.clone_last_to_prefix_list(os.environ["C4DEPLOY_CONTEXT"], fr, to_list)},

    "fire": lambda ctx, ev_abbr: {**ctx, "": fire(ctx["script"], clone_cron(), ev_abbr)},

}


def main():
    script, op = sys.argv
    if op == "main":
        main_operator(script)
    elif op.startswith("["):
        ctx = {"script": script}
        for op, *args in json.loads(op):
            ctx = handlers[op](ctx, *args)
        log("OK")
    else:
        never("bad args")


main()

# fill C4APP_REPO
# need: c4ci_prep, c4ci_up, helm
# clone_repo