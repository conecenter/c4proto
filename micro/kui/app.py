
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
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import urlopen
from secrets import token_urlsafe

def never(m): raise Exception(m)
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

def respond(h, status=200, headers=(), data=None):
    h.send_response(status)
    for header in headers: h.send_header(*header)
    h.end_headers()
    if data is not None: h.wfile.write(data)

def http_serve(addr, handlers):
    def handle(h: BaseHTTPRequestHandler):
        if {*h.headers["X-Forwarded-Groups"].split(",")} & {*environ["C4KUI_ALLOW_GROUPS"].split(",")}:
            handlers[(h.command, urlparse(h.path).path.split("/")[1])](h)
        else: respond(h,403,(),None)
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self): handle(self)
        def do_GET(self): handle(self)
    HTTPServer(addr, CallHandler).serve_forever()

def get_mail(h): return h.headers["X-Forwarded-Email"]

def build_client(pub_dir):
    client_proj = "/c4/c4client"
    write_text(f"{client_proj}/app.jsx", read_text(Path(__file__).parent/"app.jsx"))
    run(("env","-C",client_proj,"node_modules/.bin/esbuild","app.jsx","--bundle","--outfile=out.js"))
    write_text(f"{client_proj}/input.css", '@import "tailwindcss" source(none);\n@source "app.jsx";')
    run(("env","-C",client_proj,"npx","tailwindcss","-i","input.css","-o","out.css"))
    html_content = (
        '<!DOCTYPE html><html lang="en">' +
        f'<head><meta charset="UTF-8"><title>c4</title><style>{read_text(f"{client_proj}/out.css")}</style></head>' +
        f'<body><script type="module">{read_text(f"{client_proj}/out.js")}</script></body>' +
        '</html>'
    )
    Path(pub_dir).mkdir(parents=True, exist_ok=True)
    write_text(f'{pub_dir}/index.html', html_content)

def run_proxy(api_port):
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": read_text(environ["C4KUI_COOKIE_SECRET_FILE"]),
        "client_secret": read_text(environ["C4KUI_CLIENT_SECRET_FILE"]), "provider": "oidc",
        "email_domains": ["*"], "insecure_oidc_allow_unverified_email": True, "oidc_groups_claim": "cognito:groups",
        "upstreams": [f"http://127.0.0.1:{api_port}/"],
        #f"file://{pub_dir}/#/"
    }
    write_text(conf_path, "\n".join(f'{k} = {dumps(v)}' for k, v in proxy_conf.items()))
    run(("oauth2-proxy","--config",conf_path))

###

def handle_ind_login(conf,state,h):
    query = parse_qs(urlparse(h.path).query)
    name = query["cluster"]
    cluster = next(c for c in loads(environ["C4KUI_CLUSTERS"]) if c["name"] == name)
    state_key = token_urlsafe(16)
    query_params = {
        "response_type": "code", "client_id": name, "redirect_uri": f'https://{environ["C4KUI_HOST"]}/ind-auth/{name}',
        "scope": "openid profile email offline_access groups", "state": state_key
    }
    state["one_time"][state_key] = (cluster, query_params)
    respond(h, 302, [("Location",f'{cluster["issuer"]}/auth?{urlencode(query_params)}')], None)

def handle_ind_auth(state,h):
    query = parse_qs(urlparse(h.path).query)
    cluster, was_params = state["one_time"].pop(query["state"])
    params = {
        "grant_type": "authorization_code", "code": query["code"], "redirect_uri": was_params["redirect_uri"],
        "client_id": cluster["name"], "client_secret": read_text(environ[cluster["secret"]])
    }
    with urlopen(f'{cluster["issuer"]}/token',encode(urlencode(params))) as f:
        f.status == 200 or never(f"bad status: {f.status}")
        msg = loads(decode(f.read()))
    print(msg)



def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

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

def find_pod(state, h):
    msg = parse_qs(urlparse(h.path).query)
    return msg["kube_context"], state["pods"][msg["kube_context"]][msg["name"]]

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def handle_get_state(state, h):
    user_abbr = get_user_abbr(get_mail(h))
    #pod_re = re.compile(r'(de|sp)-(u|)([^a-z]+)\d*-.+-main-.+')
    fu_name = get_forward_service_name(h)
    pods = sorted((
        {
            "key": f'{kube_context}~{pod_name}', "kube_context": kube_context, "name": pod_name,
            "status": pod["status"]["phase"], "ctime": pod["metadata"]["creationTimestamp"], #todo may be age on client
            "restarts": next((cs["restartCount"] for cs in (sel(pod,"status", "containerStatuses") or [])), 0),
            "selected": selected_app_name and sel(pod,"metadata", "labels", "app") == selected_app_name
        }
        for kube_context, pods in state["pods"].items()
        for selected_app_name in [sel(state["services"][kube_context], fu_name,"spec","selector","app")]
        for pod_name, pod in pods.items() if user_abbr in pod_name
        #for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
    ), key=lambda p:p["key"])
    out_msg = { "mail": get_mail(h), "pods": pods, "clusters": [c["name"] for c in loads(environ["C4KUI_CLUSTERS"])] }
    respond(h,200,[("Content-Type","application/json")], encode(dumps(out_msg, sort_keys=True)))

def get_forward_service_name(h): return f'fu-{get_user_abbr(get_mail(h))}'

def handle_select_pod(state, h):
    debug_port = 4005
    kube_context, pod = find_pod(state, h)
    app_nm = pod["metadata"]["labels"]["app"]
    manifest = {
        "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(h) },
        "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
    }
    run((*get_kc(kube_context),"apply","-f-"), text=True, input=dumps(manifest, sort_keys=True))
    respond(h)

def handle_restart_pod(state, h):
    kube_context, pod = find_pod(state, h)
    run((*get_kc(kube_context),"delete","pod",get_name(pod)))
    respond(h)

def main():
    kube_contexts = environ["C4KUBE_CONTEXTS"].split(",")
    #
    pub_dir = "/c4/c4pub"
    api_port = 1180
    build_client(pub_dir)
    index_data = Path(f'{pub_dir}/index.html').read_bytes()
    daemon(run_proxy, api_port)
    #
    state = {"one_time":{}}
    for kind in ["pods","services"]:
        state[kind] = {}
        for kube_context in kube_contexts:
            state[kind][kube_context] = {}
            daemon(kube_watcher, state[kind][kube_context], kube_context, kind)

    handlers = {
        ("GET",""): lambda h: respond(h, 200, [("Content-Type","text/html")], index_data),
        ("GET","kop-state"): lambda h: handle_get_state(state,h),
        ("GET","ind-login"): lambda h: handle_ind_login(state,h),
        ("GET","ind-auth"): lambda h: handle_ind_auth(state,h),
        ("POST","kop-select-pod"): lambda h: handle_select_pod(state,h),
        ("POST","kop-restart-pod"): lambda h: handle_restart_pod(state,h),
    }
    http_serve(("127.0.0.1",api_port), handlers)
