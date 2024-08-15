
import json
import pathlib
import http.client
import hashlib
import base64
import time
import urllib.parse

from . import run, never_if, one, read_text, list_dir, run_text_out, log, http_exchange, http_check, Popen, never
from .cluster import get_env_values_from_pods, s3path, s3init, s3list, get_kubectl, get_pods_json, get_active_prefixes


def s3get(line, try_count):
    never_if("bad download" if try_count <= 0 else None)
    data = run(line["cat"], capture_output=True).stdout
    return data if int(line["size"]) == len(data) else s3get(line, try_count-1)


def get_hostname(kc, app):
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


def get_app_pod_cmd_prefix(kc, pods):
    pod_name = max(pod["metadata"]["name"] for pod in pods)
    return *kc, "exec", pod_name, "--", "sh", "-c"


def find_kube_context(kube_contexts, app):
    stm = ";".join((
        'import sys, subprocess as sp',
        'sys.exit(0 if sp.run(sys.argv[1:],check=True,text=True,capture_output=True,timeout=8).stdout.strip() else 1)',
    ))
    processes = [
        (kube_ctx, Popen(("python3", "-c", stm, *kc, "get", "pods", "-o", "NAME", "-l", f"app={app}")))
        for kube_ctx in kube_contexts for kc in [get_kubectl(kube_ctx)]
    ]
    return one(*[kube_ctx for kube_ctx, proc in processes if proc.wait() == 0])


def get_app_pods(kc, app): return get_pods_json(kc, ("-l", f"app={app}"))


def get_app_kc_pods(kube_contexts, app):
    kube_context = find_kube_context(kube_contexts, app)
    kc = get_kubectl(kube_context)
    return kube_context, kc, get_app_pods(kc, app)


def post_signed(kube_contexts, app, url, args, data):
    kube_context, kc, pods = get_app_kc_pods(kube_contexts, app)
    app_pod_cmd_prefix = get_app_pod_cmd_prefix(kc, pods)
    salt = run((*app_pod_cmd_prefix, "cat $C4AUTH_KEY_FILE"), capture_output=True).stdout
    host = get_hostname(kc, app)
    headers = sign(salt, [url, *args])
    http_check(*http_exchange(http.client.HTTPSConnection(host, None), "POST", url, data, headers))


def snapshot_list_dump(fr):
    for it in snapshot_list(*snapshot_prefix_resolve(fr["kube_contexts"], fr["app"])):
        log(f"\t{it['lastModified']}\t{it['size']}\t{it['key']}")


def snapshot_prefix_resolve(kube_contexts, app):
    kube_context, kc, pods = get_app_kc_pods(kube_contexts, app)
    return kube_context, one(*get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", pods))


def snapshot_list(kube_context, prefix):
    mc = s3init(get_kubectl(kube_context))
    bucket = s3path(f"{prefix}.snapshots")
    return [{**it, "cat": (*mc, "cat", f"{bucket}/{it['key']}")} for it in s3list(mc, bucket)]


def snapshot_make(to):
    post_signed(to["kube_contexts"], to["app"], "/need-snapshot", ["next"], b'')


def snapshot_get(kube_context, prefix, arg_name, try_count):
    lines = snapshot_list(kube_context, prefix)
    name = max(it["key"] for it in lines) if arg_name == "last" else arg_name
    data, = [s3get(it, try_count) for it in lines if it["key"] == name]
    return name, data


def snapshot_put(data_fn, data, kube_contexts, app, ignore):
    never_if("snapshot is too big" if len(data) > 800000000 else None)
    post_signed(kube_contexts, app, "/put-snapshot", [f"snapshots/{data_fn}", *([ignore] if ignore else [])], data)


def injection_get(path, suffix): return "\n".join(
    le.replace("?", " ")
    for path in list_dir(path) if path.endswith(suffix) for le in read_text(path).splitlines() if not le.startswith("#")
)


def injection_post(data, kube_contexts, app):
    post_signed(kube_contexts, app, "/injection", [md5s([data.encode("utf-8")]).decode("utf-8")], data.encode("utf-8"))


def injection_substitute(data, from_str, to):
    mapped = {
        "now_ms": str(int(time.time()*1000))
    }
    return data.replace(from_str, mapped[to])


# this raw put do not conform to SnapshotPatchIgnore-s including filtering S_ReadyProcess
# so if snapshot is taken and put at the same cluster
# then source should be shutdown to prevent elector depending on source's active replica
def snapshot_put_cold(data_fn, data, kube_context, to_prefix):
    kc = get_kubectl(kube_context)
    #
    while to_prefix in get_active_prefixes(kc):
        time.sleep(2)
    #
    cp = read_text("/c4/kafka-clients-classpath").strip()
    cmd = ("java", "-cp", cp, "--source", "21", "--enable-preview", "/kafka_send.java", f"{to_prefix}.inbox")
    new_offset = f"{int(run_text_out(cmd).strip())+1:016x}"
    #
    old_offset, minus, postfix = data_fn.partition("-")
    never_if(None if len(old_offset) == len(new_offset) else "bad name")
    new_fn = f"{new_offset}{minus}{postfix}"
    #
    mc = s3init(kc)
    to_bucket = f"{to_prefix}.snapshots"
    run((*mc, "mb", s3path(to_bucket)))
    never_if(None if all(it["key"] < new_fn for it in s3list(mc, s3path(to_bucket))) else "bad offset")
    run((*mc, "pipe", s3path(f"{to_bucket}/{new_fn}")), input=data)


def snapshot_get_outer(fr):
    if fr == "nil":
        return "0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e", b""
    data_path_arg = fr.get("path")
    prefix = fr.get("prefix")
    app = fr.get("app")
    never_if(None if [data_path_arg, prefix, app].count(None) == 2 else f"bad from {fr}")
    if data_path_arg is not None:
        data_path = pathlib.Path(data_path_arg)
        return data_path.name, data_path.read_bytes()
    kube_context, prefix = (
        (fr["deploy_context"], prefix) if prefix is not None else
        snapshot_prefix_resolve(fr["kube_contexts"], app) if app is not None else
        never(f"bad from {fr}")
    )
    return snapshot_get(kube_context, prefix, fr.get("name", "last"), fr.get("try_count", 3))


def snapshot_put_outer(snapshot, to):
    to_nm, data = snapshot
    if to == "pwd":
        return pathlib.Path(to_nm).write_bytes(data)
    prefix = to.get("prefix")
    app = to.get("app")
    never_if(None if [prefix, app].count(None) == 1 else f"bad to {to}")
    if prefix is not None:
        return snapshot_put_cold(to_nm, data, to["deploy_context"], prefix)
    if app is not None:
        return snapshot_put(to_nm, data, to["kube_contexts"], app, to.get("ignore", ""))
    never(f"bad to {to}")


def injection_make(fr, sub, to):
    a_dir, suffix = fr.split("*")
    injection = injection_get(a_dir, suffix)
    for s_fr, s_to in sub:
        injection = injection_substitute(injection, s_fr, s_to)
    injection_post(injection, to["kube_contexts"], to["app"])

#'ENTRYPOINT /tools/mc alias set def' +
#' $(cat $C4S3_CONF_DIR/address) $(cat $C4S3_CONF_DIR/key) $(cat $C4S3_CONF_DIR/secret) && exec sleep infinity '
