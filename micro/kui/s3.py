from json import loads, dumps
from pathlib import Path
from subprocess import check_output
from time import time

from util import rget, post

def get_ci_serve(kc): return *kc, "exec", "-i", "svc/c4cio", "--", "python3", "-u", "/ci_serve.py"

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def get_env_value(pod, name):
    for container in sel(pod, "spec", "containers") or []:
        for env in container.get("env") or []:
            if env.get("name") == name:
                return env.get("value")
    return None

def dispatch(kcs, kube_context, action, *args):
    data = Path(__file__).with_name("s3_worker.py").read_bytes() + f"\n{action}(*{dumps(args)})".encode()
    cmd = (*kcs[kube_context], "exec", "-i", "svc/c4s3client", "--", "/c4/venv/bin/python", "-u", "-")
    return check_output(cmd, input=data).decode()

def init_s3(kcs):
    mut_state_by_user = {}
    @rget("/s3")
    def load(mail, kube_context, bucket_name_like):
        s = mut_state_by_user.get(mail)
        ok = s and s["context"] == kube_context and s["bucket_name_like"] == bucket_name_like
        return {"s3contexts": sorted(kcs), "status_message": None if ok else "...", "items": s["items"] if ok else None}
    @post("/s3.search")
    def search(mail, kube_context, bucket_name_like):
        # Context validity is checked inside dispatch. On failure the exception reaches the global banner.
        items = loads( dispatch(kcs, kube_context, "handle_search", bucket_name_like))
        mut_state_by_user[mail] = {"context": kube_context, "bucket_name_like": bucket_name_like, "items": items}
    return load, search

def init_s3bucket(kcs):
    # Context validity is enforced inside dispatch; bucket names are validated remotely.
    @rget("/s3bucket")
    def load(kube_context, bucket_name):
        resp = loads( dispatch(kcs, kube_context, "handle_list_objects", bucket_name))
        objects, error = (None, "too_many") if resp.get("too_many") else (resp.get("objects") or [], None)
        return { "bucket_objects": objects, "error": error, "loaded_at": time() }
    @post("/s3bucket.reset_bucket")
    def reset(kube_context, bucket_name):
        # signals an external reset job
        # Context validity is checked inside dispatch. On failure the exception reaches the global banner.
        dispatch(kcs, kube_context, "handle_schedule_reset", bucket_name)
    # bucket names are validated remotely.
    @post("/s3bucket.make_snapshot")
    def make_snapshot(kube_context, bucket_name):
        app, = {
            e
            for d in loads(check_output((*kcs[kube_context], "get", "deployments", "-o", "json")))["items"]
            for prefix in [get_env_value(sel(d,"spec","template"), "C4INBOX_TOPIC_PREFIX")]
            if prefix and f'{prefix}.snapshots' == bucket_name
            for e in [sel(d, "metadata", "labels", "c4env")] if e
        }
        opt = { "app": app, "kube_contexts": [kube_context] }
        check_output((*get_ci_serve(kcs[kube_context]), dumps([["snapshot_make", opt]])))
    return load, reset, make_snapshot