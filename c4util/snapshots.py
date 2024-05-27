
import json
import pathlib
import http.client
import tempfile
import os
import hashlib
import base64
import time
import urllib.parse

from . import run, never, one, read_text, list_dir, run_text_out, Popen, wait_processes
from cluster import get_env_values_from_pods, s3path, s3init, s3list, get_kubectl, get_secret_data


def s3get(line):
    data = run(line["cat"], capture_output=True).stdout
    return data if int(line["size"]) == len(data) else never("bad download")


def get_hostname(kube_context, app):
    kc = get_kubectl(kube_context)
    return max(r["host"] for r in json.loads(run_text_out((*kc, "get", "ingress", "-o", "json", app)))["spec"]["rules"])


def md5s(data):
    digest = hashlib.md5()
    for a_bytes in data:
        lb = len(a_bytes).to_bytes(4, byteorder='big')
        digest.update(lb)
        digest.update(a_bytes)
    return base64.urlsafe_b64encode(digest.digest())


def sign(salt, args):
    until = int((time.time()+3600)*1000)
    u_data = [str(s).encode("utf-8") for s in [until, *args]]
    return {"x-r-signed": "=".join([urllib.parse.quote_plus(e) for e in [md5s([salt, *u_data]), *u_data]])}


def get_app_pods(kc, app):
    return json.loads(run_text_out((*kc, "get", "pods", "-o", "json", "-l", f"app={app}")))["items"]


def get_app_pod_cmd_prefix(kc, pods):
    pod_name = max(pod["metadata"]["name"] for pod in pods)
    return *kc, "exec", pod_name, "--", "sh", "-c"


def post_signed(kube_context, app, url, arg, data):
    kc = get_kubectl(kube_context)
    pods = get_app_pods(kc, app)
    app_pod_cmd_prefix = get_app_pod_cmd_prefix(kc, pods)
    salt = run((*app_pod_cmd_prefix, "cat $C4AUTH_KEY_FILE"), capture_output=True).stdout
    host = get_hostname(kube_context, app)
    headers = sign(salt, [url, arg])
    conn = http.client.HTTPSConnection(host, None)
    conn.request("POST", url, data, headers)
    resp = conn.getresponse()
    msg = resp.read()
    if resp.status != 200:
        never(f"request failed:\n{msg}")


def clone_repo(key, branch):
    dir_life = tempfile.TemporaryDirectory()
    repo = read_text(os.environ[key])
    run(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=dir_life.name)
    return dir_life


def snapshot_list(kube_context, app):
    kc = get_kubectl(kube_context)
    inbox = one(*get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", get_app_pods(kc, app)))
    mc = s3init(kc)
    bucket = s3path(f"{inbox}.snapshots")
    return [{**it, "cat": (*mc, "cat", f"{bucket}/{it['key']}")} for it in s3list(mc, bucket)]


def snapshot_get(lines, arg_name):
    name = max(it["key"] for it in lines) if arg_name == "last" else arg_name
    data, = [s3get(it) for it in lines if it["key"] == name]
    return name, data


def snapshot_write(dir_path, name, data):
    (pathlib.Path(dir_path)/name).write_bytes(data)


def snapshot_read(data_path_arg):
    data_path = pathlib.Path(data_path_arg)
    return (
        ("0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e", b"") if data_path_arg == "nil" else
        (data_path.name, data_path.read_bytes())
    )


def snapshot_put(data_fn, data, kube_context, app):
    if len(data) > 800000000:
        never("snapshot is too big")
    post_signed(kube_context, app, "/put-snapshot", f"snapshots/{data_fn}", data)


def injection_get(branch, subdir):
    dir_life = clone_repo("C4INJECTION_REPO", branch)
    return "\n".join(
        line.replace("?", " ")
        for path in list_dir(f"{dir_life.name}/{subdir}")
        for line in read_text(path).splitlines() if not line.startswith("#")
    )


def injection_post(data, kube_context, app):
    post_signed(kube_context, app, "/injection", md5s([data.encode("utf-8")]).decode("utf-8"), data)


def injection_substitute(data, from_str, to):
    mapped = {
        "now_ms": str(int(time.time()*1000))
    }
    return data.replace(from_str, mapped[to])


def with_zero_offset(fn):
    offset_len = 16
    offset, minus, postfix = fn.partition("-")
    return f"{'0' * offset_len}{minus}{postfix}" if minus == "-" and len(offset) == offset_len else None


def clone_last_to_prefix_list(kube_context, from_prefix, to_prefix_list):
    kc = get_kubectl(kube_context)
    mc = s3init(kc)
    was_buckets = {it['key'] for it in s3list(mc, s3path(""))}
    from_bucket = f"{from_prefix}.snapshots"
    files = reversed(sorted(it['key'] for it in s3list(mc, s3path(from_bucket))))
    from_fn, to_fn = next((fn, zfn) for fn in files for zfn in [with_zero_offset(fn)] if zfn)
    to_buckets = [f"{to_prefix}.snapshots" for to_prefix in to_prefix_list]
    for to_bucket in to_buckets:
        if f"{to_bucket}/" in was_buckets:
            never(f"{to_bucket} exists")
        run((*mc, "mb", s3path(to_bucket)))
    from_path = f"{from_bucket}/{from_fn}"
    to_paths = [f"{to_bucket}/{to_fn}" for to_bucket in to_buckets]
    wait_processes(Popen((*mc, "cp", s3path(from_path), s3path(to))) for to in to_paths)
