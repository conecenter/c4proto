from json import loads, dumps
from pathlib import Path
from os import environ
from subprocess import run

# C4KUI_S3_CONTEXTS and C4KUI_S3_SECRETS are deprecated

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def dispatch(s3contexts, kube_context, action, *args):
    if kube_context not in s3contexts: raise Exception("bad context")
    input = Path(__file__).with_name("s3_worker.py").read_bytes() + f"\n{action}(*{dumps(args)})".encode()
    cmd = (*get_kc(kube_context), "exec", "-i", "svc/c4s3client", "--", "/c4/venv/bin/python", "-u", "-")
    return run(cmd, check=True, input=input, capture_output=True).stdout.decode()

def init_s3(contexts):
    mut_state_by_user = {}
    s3contexts = [c["name"] for c in contexts]
    def load(mail, **_):
        return { "s3contexts": s3contexts, **mut_state_by_user.get(mail, {}) }
    def search(mail, s3context, bucket_name_like='', **_):
        def run_search():
            items = loads(dispatch(s3contexts, s3context, "handle_search", bucket_name_like))
            mut_state_by_user[mail] = { "items": items }
        return run_search
    def reset_bucket(mail, s3context, bucket_name, **_):
        def run_reset():
            try:
                dispatch(s3contexts, s3context, "handle_schedule_reset", bucket_name)
                mut_state_by_user[mail] = { "reset_message": f"Reset scheduled for {bucket_name}" }
            except Exception:
                mut_state_by_user[mail] = { "reset_message": f"Reset failed for {bucket_name}" }
                raise
        return run_reset
    def list_objects(mail, s3context, bucket_name, **_):
        def run_list():
            resp = loads(dispatch(s3contexts, s3context, "handle_list_objects", bucket_name))
            mut_state_by_user[mail] = {
                "selected_bucket": bucket_name,
                "bucket_objects": resp.get("objects", []),
                "bucket_too_many": resp.get("too_many", False),
                "reset_message": None,
            }
        return run_list
    actions = {
        "s3.load": load,
        "s3.search": search,
        "s3.reset_bucket": reset_bucket,
        "s3.list_objects": list_objects,
    }
    return actions
