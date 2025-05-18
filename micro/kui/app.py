
import subprocess
from subprocess import Popen, PIPE
from json import loads, dumps
from os import environ, kill, getpid
from threading import Thread
from signal import SIGINT, SIGTERM
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler
from re import sub
import re
from time import sleep
from sys import stderr, exc_info
from traceback import print_exc

def decode(bs): return bs.decode('utf-8')
def encode(v): return v.encode("utf-8")
def write_text(path, text): Path(path).write_text(text, encoding="utf-8", errors="strict")
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')
def run(args, **opt): return subprocess.run(args, check=True, **opt)
def log(t): print(t, file=stderr)
def fatal(f, *args):
    try: f(*args)
    except:
        print_exc()
        kill(getpid(), SIGTERM)
        raise
def daemon(*args): Thread(target=fatal, args=args, daemon=True).start()

###

def outer_handle_json_post(h, handlers):
    #todo check group
    log(h.headers)
    len_str = h.headers.get('Content-Length')
    in_msg = loads(decode(h.rfile.read(int(len_str))))
    out_msg = handlers[in_msg["op"]](in_msg)
    if out_msg:
        out_data = encode(dumps(out_msg, sort_keys=True))
        h.send_response(200)
        h.send_header("Content-Type", "application/json")
        h.end_headers()
        h.wfile.write(out_data)
    else:
        h.send_response(200)
        h.end_headers()

def http_serve(addr, handlers):
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self): outer_handle_json_post(self, handlers)
    #noinspection PyTypeChecker
    HTTPServer(addr, CallHandler).serve_forever()

def build_client(pub_dir):
    client_proj = "/c4/c4client"
    write_text(f"{client_proj}/app.jsx", read_text(Path(__file__).parent/"app.jsx"))
    run(("env","-C",client_proj,"node_modules/.bin/esbuild","app.jsx","--bundle","--outfile=out.js"))
    write_text(f"{client_proj}/input.css", '@import "tailwindcss" source(none);\n@source "app.jsx";')
    run(("env","-C",client_proj,"npx","tailwindcss","-i","input.css","-o","out.css"))
    html_content = (
        '<!DOCTYPE html><html lang="en">' +
        f'<head><meta charset="UTF-8"><title>c4</title><styles>{read_text(f"{client_proj}/out.css")}</styles></head>' +
        f'<body><script type="module">{read_text(f"{client_proj}/out.js")}</script></body>' +
        '</html>'
    )
    Path(pub_dir).mkdir(parents=True, exist_ok=True)
    write_text(f'{pub_dir}/index.html', html_content)

def run_proxy(pub_dir, api_port):
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": read_text(environ["C4KUI_COOKIE_SECRET_FILE"]),
        "client_secret": read_text(environ["C4KUI_CLIENT_SECRET_FILE"]), "provider": "oidc",
        "email_domains": ["*"], "insecure_oidc_allow_unverified_email": True, "oidc_groups_claim": "cognito:groups",
        "upstreams": [f"file://{pub_dir}/#/", f"http://127.0.0.1:{api_port}/kop"],
    }
    write_text(conf_path, "\n".join(f'{k} = {dumps(v)}' for k, v in proxy_conf.items()))
    run(("oauth2-proxy","--config",conf_path))

###

def get_kc(kube_context): return "kubectl","--context",kube_context

def get_name(obj): return obj["metadata"]["name"]

def kube_watcher(state, kube_context, kind):
    while True:
        cmd = (*get_kc(kube_context),"get","--raw",f"/api/v1/namespaces/c4test/{kind}?watch")
        with Popen(cmd, text=True, stdout=PIPE) as proc:
            state.clear()
            for line in proc.stdout:
                ev = loads(line)
                name = get_name(ev["object"])
                match ev["type"]:
                    case "ADDED" | "MODIFIED": state[name] = ev["object"] #,"kube_context":kube_context,"key":f'{kube_context}~{name}'}
                    case "DELETED": del state[name]
        sleep(2)

def find_pod(state, msg): return state[msg["kube_context"]]["pods"][msg["name"]]

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def handle_get_state(state, msg):
    user_abbr = get_user_abbr(msg["mail"])
    pod_re = re.compile(r'(de|sp)-(u|)([^a-z]+)\d*-.+-main')
    fu_name = get_forward_service_name(msg)
    pods = sorted((
        {
            "key": f'{kube_context}~{pod_name}', "kube_context": kube_context, "name": pod_name,
            "status": pod["status"]["phase"], "ctime": pod["metadata"]["creationTimestamp"], #todo may be age on client
            "restarts": next((cs["restartCount"] for cs in (sel(pod,"status", "containerStatuses") or [])), 0),
            "selected": selected_app_name and sel(pod,"metadata", "labels", "app") == selected_app_name
        }
        for kube_context, context_st in state.items()
        for selected_app_name in [sel(context_st,"services",fu_name,"spec","selector","app")]
        for pod_name, pod in context_st["pods"].items()
        for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
    ), key=lambda p:p["key"])
    return { "mail": msg["mail"], "pods": pods }

def get_forward_service_name(msg): return f'fu-{get_user_abbr(msg["mail"])}'

def handle_select_pod(state, msg):
    debug_port = 4005
    pod = find_pod(state, msg)
    app_nm = pod["metadata"]["labels"]["app"]
    manifest = {
        "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(msg) },
        "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
    }
    run((*get_kc(pod["kube_context"]),"apply","-f-"), text=True, input=dumps(manifest, sort_keys=True))

def handle_restart_pod(state, msg):
    pod = find_pod(state, msg)
    run((*get_kc(pod["kube_context"]),"delete","pod",get_name(pod)))

def get_handlers(state): return {
    "get_state": lambda m: handle_get_state(state, m),
    "select_pod": lambda m: handle_select_pod(state, m),
    "restart_pod": lambda m: handle_restart_pod(state, m),
}

def main():
    kube_contexts = environ["C4KUBE_CONTEXTS"].split(",")
    #
    pub_dir = "/c4/c4pub"
    api_port = 1180
    build_client(pub_dir)
    daemon(run_proxy, pub_dir, api_port)
    #
    state = {}
    for kube_context in kube_contexts:
        state[kube_context] = {}
        for kind in ["pods","services"]:
            state[kube_context][kind] = {}
            daemon(kube_watcher, state[kube_context][kind], kube_context, kind)
    http_serve(("127.0.0.1",api_port), get_handlers(state))
