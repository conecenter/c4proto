
import sys
import subprocess
import json
import pathlib
import http.client
import tempfile
import os
import hashlib
import base64
import time
import urllib


def never(a): raise Exception(a)


def read_text(path): return path.read_text(encoding='utf-8', errors='strict')


def debug_args(hint, args):
    print(hint+" "+" ".join(str(a) for a in args), file=sys.stderr)
    return args


def run(args, **opt): return subprocess.run(debug_args("running:", args), check=True, **opt)


def get_kubectl(kube_context): return "kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "--context", kube_context


def s3init(kube_context):
    kc = get_kubectl(kube_context)
    s3pods_str = run((*kc, "get", "pods", "-o", "name", "-l", "c4s3client"), text=True, capture_output=True).stdout
    s3pod = max(s3pods_str.splitlines())
    return *kc, "exec", s3pod, "--", "/tools/mc"


def s3list(mc, bucket):
    return [
        {**l, "cat": (*mc, "cat", f"def/{bucket}/{l['key']}")}
        for line in run((*mc, "ls", "--json", f"def/{bucket}"), text=True, capture_output=True).stdout.splitlines()
        for l in [json.loads(line)]
    ]


def s3get(line):
    data = run(line["cat"], capture_output=True).stdout
    return data if int(line["size"]) == len(data) else never("bad download")


def get_hostname(kube_context, app):
    kc = get_kubectl(kube_context)
    ingress = json.loads(run((*kc, "get", "ingress", "-o", "json", app), text=True, capture_output=True).stdout)
    return max(r["host"] for r in ingress["spec"]["rules"])


def md5s(data):
    digest = hashlib.md5()
    #print(data)
    for bytes in data:
        l = len(bytes).to_bytes(4, byteorder='big')
        digest.update(l)
        digest.update(bytes)
    return base64.urlsafe_b64encode(digest.digest())


def sign(salt, args):
    until = int((time.time()+3600)*1000)
    u_data = [str(s).encode("utf-8") for s in [until, *args]]
    return {"x-r-signed": "=".join([urllib.parse.quote_plus(e) for e in [md5s([salt, *u_data]), *u_data]])}


def get_app_pod_cmd_prefix(kube_context, app):
    kc = get_kubectl(kube_context)
    pods = json.loads(run((*kc, "get", "pods", "-o", "json", "-l", f"app={app}"), text=True, capture_output=True).stdout)["items"]
    pod_name = max(pod["metadata"]["name"] for pod in pods)
    return *kc, "exec", pod_name, "--", "sh", "-c"


def post_signed(kube_context, app, url, arg, data):
    app_pod_cmd_prefix = get_app_pod_cmd_prefix(kube_context, app)
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
    repo = read_text(pathlib.Path(os.environ[key]))
    run(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=dir_life.name)
    return dir_life


def op_snapshot_list(ctx, kube_context, app):
    app_pod_cmd_prefix = get_app_pod_cmd_prefix(kube_context, app)
    inbox = run((*app_pod_cmd_prefix, "echo -n $C4INBOX_TOPIC_PREFIX"), text=True, capture_output=True).stdout
    mc = s3init(kube_context)
    ctx["snapshot_list"] = s3list(mc, f"{inbox}.snapshots")


def op_dump(ctx):
    snapshot_list = ctx.get("snapshot_list")
    if snapshot_list:
        print("snapshot_list:")
        for l in snapshot_list:
            print(f"\t{l['lastModified']}\t{l['size']}\t{l['key']}")
    data = ctx.get("injection")
    if data:
        print(f"injection:\n{data}")


def op_snapshot_get(ctx, arg_name):
    lines = ctx["snapshot_list"]
    name = max(l["key"] for l in lines) if arg_name == "last" else arg_name
    data, = [s3get(l) for l in lines if l["key"] == name]
    ctx["snapshot"] = (name, data)


def op_snapshot_write(ctx, dir_path):
    name, data = ctx["snapshot"]
    (pathlib.Path(dir_path)/name).write_bytes(data)


def op_snapshot_read(ctx, data_path_arg):
    data_path = pathlib.Path(data_path_arg)
    ctx["snapshot"] = (
        ("0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e", b"") if data_path_arg == "nil" else
        (data_path.name, data_path.read_bytes())
    )


def op_snapshot_put(ctx, kube_context, app):
    data_fn, data = ctx["snapshot"]
    post_signed(kube_context, app, "/put-snapshot", f"snapshots/{data_fn}", data)


def op_injection_get(ctx, branch, subdir):
    dir_life = clone_repo("C4INJECTION_REPO", branch)
    ctx["injection"] = "\n".join(
        line.replace("?", " ")
        for path in sorted((pathlib.Path(dir_life.name)/subdir).iterdir())
        for line in read_text(path).splitlines() if not line.startswith("#")
    )


def op_injection_post(ctx, kube_context, app):
    data = ctx["injection"]
    post_signed(kube_context, app, "/injection", md5s([data.encode("utf-8")]).decode("utf-8"), data)


def op_injection_substitute(ctx, from_str, to):
    mapped = {
        "now_ms": str(int(time.time()*1000))
    }
    ctx["injection"] = ctx["injection"].replace(from_str, mapped[to])


def op_injection_set(ctx, value):
    ctx["injection"] = value


def main_operator(script):
    dir_life = clone_repo("C4INJECTION_REPO", os.environ["C4CRON_BRANCH"])
    path = pathlib.Path(dir_life.name) / os.environ["C4CRON_SUB_PATH"]
    last_tm_abbr = ""
    while True:
        tm = time.gmtime()
        tm_abbr = "ETKNRLP"[tm.tm_wday] + time.strftime("%H:%M", tm)
        if last_tm_abbr == tm_abbr:
            continue
        last_tm_abbr = tm_abbr
        run(("git", "pull"), cwd=dir_life.name)
        for step_str in [json.dumps(steps) for times, steps in json.loads(read_text(path)) if tm_abbr in times]:
            subprocess.Popen(("python3", "-u", script, step_str))
        time.sleep(30)


handlers = {
    "dump": op_dump,
    "snapshot_list": op_snapshot_list,
    "snapshot_get": op_snapshot_get,
    "snapshot_write": op_snapshot_write,
    "snapshot_read": op_snapshot_read,
    "snapshot_put": op_snapshot_put,
    "injection_get": op_injection_get,
    "injection_post": op_injection_post,
    "injection_substitute": op_injection_substitute,
    "injection_set": op_injection_set,
}


def main():
    script, op = sys.argv
    if op == "main":
        main_operator(script)
    elif op.startswith("["):
        ctx = {}
        for op, *args in json.loads(op):
            handlers[op](ctx, *args)
        print("OK", file=sys.stderr)
    else:
        never()

main()
# cp_inj inj per_cp_inj

