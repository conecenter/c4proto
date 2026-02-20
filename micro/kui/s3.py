from json import loads, dumps
from pathlib import Path
from subprocess import run, check_output
from queue import SimpleQueue
from time import monotonic, time

# C4KUI_S3_CONTEXTS and C4KUI_S3_SECRETS are deprecated

def get_ci_serve(kcp, kube_context):
    return *kcp, kube_context, "exec", "-i", "svc/c4cio", "--", "python3", "-u", "/ci_serve.py"

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def get_env_value(pod, name):
    for container in sel(pod, "spec", "containers") or []:
        for env in container.get("env") or []:
            if env.get("name") == name:
                return env.get("value")
    return None

def dispatch(kcp, s3contexts, kube_context, action, *args):
    if kube_context not in s3contexts: raise Exception("bad context")
    input = Path(__file__).with_name("s3_worker.py").read_bytes() + f"\n{action}(*{dumps(args)})".encode()
    cmd = (*kcp, kube_context, "exec", "-i", "svc/c4s3client", "--", "/c4/venv/bin/python", "-u", "-")
    return run(cmd, check=True, input=input, capture_output=True).stdout.decode()

def _context_names(contexts): return [c["name"] for c in contexts]

def init_s3(contexts, kcp):
    s3contexts = _context_names(contexts)
    mut_state_by_user = {}
    def replace_state(mail, context, bucket_name_like, items, status_message):
        # Always overwrite the whole snapshot to keep diffing simple and avoid stale entries.
        mut_state_by_user[mail] = {
            "context": context, "bucket_name_like": bucket_name_like, "items": items,
            "status_message": status_message,
        }
    def replace_state_error(mail, status_message):
        replace_state(mail, None, None, None, status_message)
    def load(mail, filter_kube_context='', bucket_name_like='', **_):
        state = mut_state_by_user.get(mail)
        message, items = (
            (state["status_message"], state["items"])
            if state and state["status_message"] is not None
               or state and state["context"] == filter_kube_context and state["bucket_name_like"] == bucket_name_like
            else ("...", None)
        )
        return { "s3contexts": s3contexts, "status_message": message, "items": items }
    def search(mail, kube_context, bucket_name_like='', **_):
        def run_search():
            # Context validity is checked inside dispatch, no need extra check here.
            try:
                items = loads(dispatch(kcp, s3contexts, kube_context, "handle_search", bucket_name_like))
                replace_state(mail, kube_context, bucket_name_like, items=items, status_message=None)
            except Exception:
                # Drop stale list; user must re-run search after resolving the issue.
                replace_state_error(mail, status_message="Search failed. Try again later.")
                raise
        return run_search
    return {
        "s3.load": load,
        "s3.search": search,
    }

def init_s3bucket(contexts, kcp):
    s3contexts = _context_names(contexts)
    bucket_cache = {}
    requests = SimpleQueue()
    def load_bucket(mail, bucket_kube_context=None, bucket_name=None, **_):
        requests.put((bucket_kube_context, bucket_name, True))
        cached = bucket_cache.get((bucket_kube_context, bucket_name))
        return cached["state"] if cached else create_response(None, None, None)
    def refresh_bucket(mail, kube_context, bucket_name, **_):
        requests.put((kube_context, bucket_name, False))
    def create_response(objects, error, loaded_at):
        return { "bucket_objects": objects, "error": error, "loaded_at": loaded_at }
    def replace_state(key, state, ttl): bucket_cache[key] = { "state": state, "expires_at": monotonic() + ttl }
    def watcher():
        while True:
            kube_context, bucket_name, is_soft = requests.get()
            key = (kube_context, bucket_name)
            cached = bucket_cache.get(key)
            if cached and is_soft and cached["expires_at"] > monotonic():
                continue
            try:
                resp = loads(dispatch(kcp, s3contexts, kube_context, "handle_list_objects", bucket_name))
                if resp.get("too_many"):
                    msg = "Unable to display more than 1000 objects. Use CLI tools for detailed listing."
                    replace_state(key, create_response(None, msg, time()), 300)
                else:
                    replace_state(key, create_response(resp.get("objects") or [],None, time()), 15)
            except Exception:
                replace_state(key, create_response(None, "Failed. Try again later.", time()), 300)
    # Context validity is enforced inside dispatch; bucket names are validated remotely.
    # Treating “missing” args the same as “invalid”.
    # 5‑minute retry window keeps the exceptions down to one per cycle.
    def reset_bucket(mail, kube_context, bucket_name, **_):
        def run_reset():
            # Context validity is checked inside dispatch, no need extra check here.
            # Bucket list stays stale until user triggers a fresh search.
            try:
                dispatch(kcp, s3contexts, kube_context, "handle_schedule_reset", bucket_name)
                requests.put((kube_context, bucket_name, False))
            except Exception:
                key = (kube_context, bucket_name)
                msg = f"Reset failed for {bucket_name}. Try again later."
                replace_state(key, create_response(None, msg, time()), 15)
                raise
        return run_reset
    # bucket names are validated remotely.
    def make_snapshot(mail, kube_context, bucket_name, **_):
        if kube_context not in s3contexts: raise Exception("bad context")
        def run_make():
            try:
                app, = {
                    e
                    for d in loads(check_output((*kcp, kube_context, "get", "deployments", "-o", "json")))["items"]
                    for prefix in [get_env_value(sel(d,"spec","template"), "C4INBOX_TOPIC_PREFIX")]
                    if prefix and f'{prefix}.snapshots' == bucket_name
                    for e in [sel(d, "metadata", "labels", "c4env")] if e
                }
                opt = { "app": app, "kube_contexts": [kube_context] }
                check_output((*get_ci_serve(kcp, kube_context), dumps([["snapshot_make", opt]])))
            except Exception:
                key = (kube_context, bucket_name)
                msg = f"Snapshot making failed for {bucket_name}. Try again later."
                replace_state(key, create_response(None, msg, time()), 15)
                raise
        return run_make
    return (
        {
            "s3bucket.load": load_bucket,
            "s3bucket.refresh": refresh_bucket,
            "s3bucket.reset_bucket": reset_bucket,
            "s3bucket.make_snapshot": make_snapshot,
        },
        watcher,
    )


{"app": "{from}-{project}-{ms}", "kube_contexts": ["{kube_context}"]}