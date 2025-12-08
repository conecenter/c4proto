
from json import loads
from http.client import HTTPConnection, HTTPSConnection
import hashlib
import base64
import time
import urllib.parse
import re
from pathlib import Path

from util import run, never_if, one, read_text, list_dir, run_text_out, http_exchange, http_check, Popen, never, run_no_die
from cluster import get_prefixes_from_pods, s3path, s3init, s3list, get_kubectl, get_pods_json, wait_no_active_prefix,\
    get_all_contexts


def s3get(cmd, size, try_count):
    never_if("bad download" if try_count <= 0 else None)
    data = run(cmd, capture_output=True).stdout
    return data if size == len(data) else s3get(cmd, size, try_count-1)


def get_hostname(kc, app):
    return max(
        r["host"]
        for ingress in loads(run_text_out((*kc, "get", "ingress", "-o", "json", "-l", f"c4env={app}")))["items"]
        for r in ingress["spec"]["rules"]
    )


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
        (kube_ctx, Popen(("python3", "-c", stm, *kc, "get", "pods", "-o", "NAME", "-l", f"c4env={app}")))
        for kube_ctx in kube_contexts for kc in [get_kubectl(kube_ctx)]
    ]
    return one(*[kube_ctx for kube_ctx, proc in processes if proc.wait() == 0])


def get_app_pods(kc, app): return get_pods_json(kc, ("-l", f"c4env={app}"))


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
    http_check(*http_exchange(HTTPSConnection(host, None), "POST", url, data, headers))


def snapshot_prefix_resolve(opt):
    if opt["type"] == "prefix":
        return opt["kube_context"], opt["prefix"]
    elif opt["type"] == "app":
        kube_context, kc, pods = get_app_kc_pods(opt["kube_contexts"], opt["app"])
        return kube_context, one(*get_prefixes_from_pods(pods))
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
def snapshot_copy(env, def_kafka_addr, fr, to):
    deploy_context = env["C4DEPLOY_CONTEXT"]
    fr = with_kube_contexts(deploy_context,fr)
    fr_kube_context = None
    fr_bucket = None
    if fr["type"] == "nil":
        name, get_data = ("0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e", lambda: b"")
    else:
        fr_kube_context, fr_prefix = snapshot_prefix_resolve(fr)
        fr_mc = s3init(get_kubectl(fr_kube_context))
        fr_bucket = f"{fr_prefix}.snapshots"
        lines = s3list(fr_mc, s3path(fr_bucket))
        arg_name = fr.get("name", "last")
        name = max(it["key"] for it in lines) if arg_name == "last" else arg_name
        size = one(*[int(it["size"]) for it in lines if it["key"] == name])
        get_data = lambda: s3get((*fr_mc, "cat", s3path(f"{fr_bucket}/{name}")), size, fr.get("try_count", 3))
    to = with_kube_contexts(deploy_context,to)
    if to["type"] == "prefix":
        to_kube_context = to["kube_context"]
        to_kc = get_kubectl(to_kube_context)
        to_prefix = to["prefix"]
        s3sign = Path(__file__).with_name("s3sign.java")
        java = ("java", "--source", "21", "--enable-preview")
        #
        if "no_wait" not in to: wait_no_active_prefix(to_kc, to_prefix)
        #
        s3exec =  (*to_kc, "exec", "-i", "svc/c4s3client", "--" )
        offset_res = run_text_out((*s3exec, "python3", "-u", "/app/main.py", "produce_one", f"{to_prefix}.inbox", ""))
        new_offset = f'{int(offset_res)+1:016x}'
        #
        old_offset, minus, postfix = name.partition("-")
        never_if(None if minus == "-" and len(old_offset) == len(new_offset) else "bad name")
        new_fn = f"{new_offset}{minus}{postfix}"
        #
        to_mc = (*s3exec, "/tools/mc")
        to_bucket = f"{to_prefix}.snapshots"
        run_no_die((*to_mc, "mb", s3path(to_bucket)))
        never_if([
            f'bad offset: has {it["key"]} >= new {new_fn}'
            for it in s3list(to_mc, s3path(to_bucket)) if it["key"] >= new_fn
        ])
        if fr_kube_context == deploy_context and to_kube_context == deploy_context:
            source = f"{fr_bucket}/{name}"
            url = f"/{to_bucket}/{new_fn}"
            can_in = f"PUT\n\n\n[date]\nx-amz-copy-source:{source}\n{url}"
            add_head = {
                k: v for l in run_text_out((*java, str(s3sign), can_in)).splitlines()
                for k, splitter, v in [l.partition(":")] if splitter == ":"
            }
            proto, splitter, host = read_text(f'{env["C4S3_CONF_DIR"]}/address').partition("://")
            never_if(None if proto == "http" and splitter == "://" else "bad address")
            headers = {**add_head, "x-amz-copy-source": source}
            http_check(*http_exchange(HTTPConnection(host, None), "PUT", url, b'', headers))
        else: s3put(to_mc, s3path(f"{to_bucket}/{new_fn}"), get_data(), to.get("try_count", 3))
    elif to["type"] == "app":
        data = get_data()
        ignore = [iv for iv in [to.get("ignore")] if iv]
        never_if("snapshot is too big" if len(data) > 800000000 else None)
        post_signed(to["kube_contexts"], to["app"], "/put-snapshot", [f"snapshots/{name}", *ignore], data)
    else:
        never(f"bad to {to}")


def s3put(mc, path, data, try_count):
    never_if("bad upload" if try_count <= 0 else None)
    run((*mc, "pipe", path), input=data)
    if loads(run_text_out((*mc, "ls", "--json", path)))["size"] != len(data): s3put(mc, path, data, try_count-1)


def with_kube_contexts(deploy_context, opt): return (
    {"type": "nil"} if opt == "nil" else
    {
        **opt, "type": "app", "kube_contexts": (
            [deploy_context] if "kube_contexts" not in opt else
            get_all_contexts() if opt["kube_contexts"] == "all" else
            opt["kube_contexts"]
        )
    } if "app" in opt and "prefix" not in opt else
    { **opt, "type": "prefix", "kube_context": opt.get("kube_context", deploy_context) } if (
        "prefix" in opt and "app" not in opt and
        re.fullmatch(r'[a-z]{2}-[a-z\d]+-[a-z]+-[a-z]+(\.\d)?', opt["prefix"])
    ) else
    never(f"bad from/to {opt}")
)

def injection_substitutes(injection, sub):
    for s_fr, s_to in sub:
        injection = injection_substitute(injection, s_fr, s_to)
    return injection

def injection_make(deploy_context, fr, sub, to):
    to = with_kube_contexts(deploy_context, to)
    injection = injection_substitutes(injection_get(*fr.split("*")), sub)
    injection_post(injection, to["kube_contexts"], to["app"])

def injection_copy(deploy_context, fr, sub, to):
    to = with_kube_contexts(deploy_context, to)
    injection = injection_substitutes(injection_get(*fr.split("*")), sub)
    to_kc = get_kubectl(to["kube_context"])
    to_prefix = to["prefix"]
    part = to["part"]
    never_if(part not in ("del","add"))
    if "no_wait" not in to: wait_no_active_prefix(to_kc, to_prefix)
    s3put(s3init(to_kc), s3path(f"{to_prefix}.snapshots/.injection.{part}"), injection.encode(), to.get("try_count", 3))
