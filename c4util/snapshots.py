
import json
import pathlib
import http.client
import hashlib
import base64
import time
import urllib.parse
import re

from . import run, never_if, one, read_text, list_dir, run_text_out, http_exchange, http_check, Popen, never, log
from .cluster import get_env_values_from_pods, s3path, s3init, s3list, get_kubectl, get_pods_json, get_active_prefixes,\
    get_all_contexts



def s3get(cmd, size, try_count):
    never_if("bad download" if try_count <= 0 else None)
    data = run(cmd, capture_output=True).stdout
    return data if size == len(data) else s3get(cmd, size, try_count-1)


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


def snapshot_list_dump(deploy_context, fr):
    kube_context, prefix = snapshot_prefix_resolve(deploy_context, with_kube_contexts(deploy_context,fr))
    mc = s3init(get_kubectl(kube_context))
    log(run_text_out((*mc, "ls", s3path(f"{prefix}.snapshots"))))


def snapshot_prefix_resolve(deploy_context, opt):
    if opt["type"] == "prefix":
        return deploy_context, opt["prefix"]
    elif opt["type"] == "app":
        kube_context, kc, pods = get_app_kc_pods(opt["kube_contexts"], opt["app"])
        return kube_context, one(*get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", pods))
    else:
        never("bad from/to")


def snapshot_make(deploy_context, to):
    to = with_kube_contexts(deploy_context, to)
    post_signed(to["kube_contexts"], to["app"], "/need-snapshot", ["next"], b'')


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


# prefix raw put do not conform to SnapshotPatchIgnore-s including filtering S_ReadyProcess
# but elector uses currentTxLogName to avoid the bug
def snapshot_copy(env, fr, to):
    deploy_context = env["C4DEPLOY_CONTEXT"]
    fr = with_kube_contexts(deploy_context,fr)
    fr_kube_context = None
    fr_bucket = None
    if fr["type"] == "nil":
        name, get_data = ("0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e", lambda: b"")
    else:
        fr_kube_context, fr_prefix = snapshot_prefix_resolve(deploy_context, fr)
        fr_mc = s3init(get_kubectl(fr_kube_context))
        fr_bucket = f"{fr_prefix}.snapshots"
        lines = s3list(fr_mc, s3path(fr_bucket))
        arg_name = fr.get("name", "last")
        name = max(it["key"] for it in lines) if arg_name == "last" else arg_name
        size = one(*[int(it["size"]) for it in lines if it["key"] == name])
        get_data = lambda: s3get((*fr_mc, "cat", s3path(f"{fr_bucket}/{name}")), size, fr.get("try_count", 3))
    to = with_kube_contexts(deploy_context,to)
    if to["type"] == "prefix":
        to_kc = get_kubectl(deploy_context)
        to_prefix = to["prefix"]
        util_dir = f'{env["C4CI_PROTO_DIR"]}/c4util'
        java = ("java", "--source", "21", "--enable-preview")
        #
        while to_prefix in get_active_prefixes(to_kc):
            time.sleep(2)
        #
        cp = read_text("/c4/kafka-clients-classpath").strip()
        cmd = (*java, "-cp", cp, f"{util_dir}/kafka_send.java", f"{to_prefix}.inbox")
        new_offset = f"{int(run_text_out(cmd).strip())+1:016x}"
        #
        old_offset, minus, postfix = name.partition("-")
        never_if(None if minus == "-" and len(old_offset) == len(new_offset) else "bad name")
        new_fn = f"{new_offset}{minus}{postfix}"
        #
        to_mc = s3init(to_kc)
        to_bucket = f"{to_prefix}.snapshots"
        run((*to_mc, "mb", s3path(to_bucket)))
        never_if([
            f'bad offset: has {it["key"]} >= new {new_fn}'
            for it in s3list(to_mc, s3path(to_bucket)) if it["key"] >= new_fn
        ])
        if fr_kube_context == deploy_context:
            source = f"{fr_bucket}/{name}"
            url = f"/{to_bucket}/{new_fn}"
            can_in = f"PUT\n\n\n[date]\nx-amz-copy-source:{source}\n{url}"
            add_head = {
                k: v for l in run_text_out((*java, f"{util_dir}/s3sign.java", can_in)).splitlines()
                for k, splitter, v in [l.partition(":")] if splitter == ":"
            }
            proto, splitter, host = read_text(f'{env["C4S3_CONF_DIR"]}/address').partition("://")
            never_if(None if proto == "http" and splitter == "://" else "bad address")
            headers = {**add_head, "x-amz-copy-source": source}
            http_check(*http_exchange(http.client.HTTPConnection(host, None), "PUT", url, b'', headers))
        else:
            run((*to_mc, "pipe", s3path(f"{to_bucket}/{new_fn}")), input=get_data())
    elif to["type"] == "app":
        data = get_data()
        ignore = [iv for iv in [to.get("ignore")] if iv]
        never_if("snapshot is too big" if len(data) > 800000000 else None)
        post_signed(to["kube_contexts"], to["app"], "/put-snapshot", [f"snapshots/{name}", *ignore], data)
    else:
        never(f"bad to {to}")


def with_kube_contexts(deploy_context, opt): return (
    {"type": "nil"} if opt == "nil" else
    {
        **opt, "type": "app", "kube_contexts": (
            [deploy_context] if "kube_contexts" not in opt else
            get_all_contexts() if opt["kube_contexts"] == "all" else
            opt["kube_contexts"]
        )
    } if "app" in opt and "prefix" not in opt else
    {**opt, "type": "prefix"} if (
        "prefix" in opt and "app" not in opt and
        re.fullmatch(r'[a-z]{2}-[a-z\d]+-[a-z]+-[a-z]+(\.\d)?', opt["prefix"])
    ) else
    never(f"bad from/to {opt}")
)

def injection_make(deploy_context, fr, sub, to):
    to = with_kube_contexts(deploy_context, to)
    a_dir, suffix = fr.split("*")
    injection = injection_get(a_dir, suffix)
    for s_fr, s_to in sub:
        injection = injection_substitute(injection, s_fr, s_to)
    injection_post(injection, to["kube_contexts"], to["app"])
