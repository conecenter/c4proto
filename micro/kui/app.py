
import subprocess
from subprocess import Popen, PIPE
from json import loads, dumps
from os import environ, kill, getpid
from threading import Thread
from signal import SIGINT, SIGTERM
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from re import sub
import re
from time import sleep, monotonic
from sys import stderr, exc_info
from traceback import print_exc
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import urlopen
from secrets import token_urlsafe
from base64 import b64encode

def one(it): return it
def never(m): raise Exception(m)
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

def http_serve(addr, handlers):
    handler_dict = {(method, op): (auth, args, f) for auth, method, op, args, f in handlers}
    allow_groups = {*(environ["C4KUI_ALLOW_GROUPS"] or never("need groups")).split(",")}
    def handle(h: BaseHTTPRequestHandler):
        parsed_path = urlparse(h.path)
        need_auth, arg_names, handler = handler_dict[(h.command, parsed_path.path.split("/")[1])]
        if need_auth and not ({*(h.headers["X-Forwarded-Groups"] or never("need groups")).split(",")} & allow_groups):
            log(f'groups: {h.headers["X-Forwarded-Groups"]}')
            never("need auth")
        q = parse_qs(parsed_path.query) # skips ""-s
        res = handler({k: (h.headers["X-Forwarded-Email"] if k == "mail" else one(*q.get(k,['']))) for k in arg_names})
        status, headers, data = (
            #res if isinstance(res, tuple) else
            (200, (), None) if res is None else
            (int(res), (), None) if res.isdigit() else
            (302, [("Location",res)], None) if res.startswith("http") else
            (200, [("Content-Type","text/html")], res.encode()) if res.startswith("<!DOCTYPE html>") else
            (200, [("Content-Type","application/json")], res.encode()) if res.startswith("{") else
            never("bad result")
        )
        h.send_response(status)
        for header in headers: h.send_header(*header)
        h.end_headers()
        if data is not None: h.wfile.write(data)
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self): handle(self)
        def do_GET(self): handle(self)
    ThreadingHTTPServer(addr, CallHandler).serve_forever()

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

def run_proxy(api_port,handlers):
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": read_text(environ["C4KUI_COOKIE_SECRET_FILE"]),
        "client_secret": read_text(environ["C4KUI_CLIENT_SECRET_FILE"]), "provider": "oidc",
        "email_domains": ["*"], "insecure_oidc_allow_unverified_email": True, "oidc_groups_claim": "cognito:groups",
        "upstreams": [f"http://127.0.0.1:{api_port}/"], #f"file://{pub_dir}/#/"
        "skip_auth_routes": [f'{method}=^/{path_part}' for auth, method, path_part, *etc in handlers if not auth],
    }
    write_text(conf_path, "\n".join(f'{k} = {dumps(v)}' for k, v in proxy_conf.items()))
    run(("oauth2-proxy","--config",conf_path))

###

def set_one_time(mut_one_time, key, value): mut_one_time[key] = (monotonic(), value)
def pop_one_time(mut_one_time, key):
    tm, value = mut_one_time.pop(key)
    return value if monotonic() - tm < 30 else never("expired")

def get_redirect_uri(): return f'https://{environ["C4KUI_HOST"]}/ind-auth'

def handle_ind_login(mut_one_time,name):
    cluster = one(*(c for c in loads(environ["C4KUI_CLUSTERS"]) if c["name"] == name))
    state_key = token_urlsafe(16)
    query_params = {
        "response_type": "code", "client_id": name, "redirect_uri": get_redirect_uri(),
        "scope": "openid profile email offline_access groups", "state": state_key
    }
    set_one_time(mut_one_time, state_key, cluster)
    return f'https://{cluster["issuer"]}/auth?{urlencode(query_params)}'

def handle_ind_auth(mut_one_time,mail,state,code):
    cluster = pop_one_time(mut_one_time,state)
    name = cluster["name"]
    client_secret = loads(read_text(environ["C4KUI_CLIENT_SECRETS"]))[name]
    params = {
        "grant_type": "authorization_code", "code": code, "redirect_uri": get_redirect_uri(),
        "client_id": name, "client_secret": client_secret
    }
    log(f'fetching token for {mail} / {name}')
    with urlopen(f'https://{cluster["issuer"]}/token',urlencode(params).encode()) as f:
        f.status == 200 or never(f"bad status: {f.status}")
        msg = loads(f.read().decode())
    log(f'fetched token for {mail} / {name}')
    contexts = [c for c in loads(environ["C4KUI_CONTEXTS"]) if c["cluster"] == name]
    cert_content = b64encode(Path(environ["C4KUI_CERTS"].replace("{name}", name)).read_bytes()).decode()
    server = f'https://{environ["C4KUI_API_SERVER"].replace("{name}", name)}:{cluster.get("port","443")}'
    out_msg = {
        "config_commands": [
            ["set-cluster", name, "--server", server],
            ["set", f'clusters.{name}.certificate-authority-data', cert_content],
            [
                "set-credentials", name,
                "--auth-provider", "oidc",
                "--auth-provider-arg", f'idp-issuer-url=https://{cluster["issuer"]}',
                "--auth-provider-arg", f'client-id={name}',
                "--auth-provider-arg", f'client-secret={client_secret}',
                "--auth-provider-arg", f'refresh-token={msg["refresh_token"]}',
                "--auth-provider-arg", f'id-token={msg["id_token"]}',
            ],
            *(["set-context",c["name"],"--cluster",name,"--user",name,"--namespace",c["ns"]] for c in contexts)
        ],
        "pod_selectors": [f'{c["name"]}~svc~{get_forward_service_name(mail)}' for c in contexts if c.get("watch")],
        "redirect": f'https://{environ["C4KUI_HOST"]}/#last_cluster={name}',
    }
    a_code = token_urlsafe(16)
    set_one_time(mut_one_time, a_code, out_msg)
    return f'http://localhost:1979/agent-auth?{urlencode({"code":a_code})}'

def handle_agent_auth(mut_one_time,code): return dumps(pop_one_time(mut_one_time,code), sort_keys=True)

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def get_name(obj): return obj["metadata"]["name"]

def kube_watcher(mut_state, kube_context, kind):
    while True:
        cmd = (*get_kc(kube_context),"get","--raw",f"/api/v1/namespaces/c4test/{kind}?watch")
        with Popen(cmd, text=True, stdout=PIPE) as proc:
            mut_state.clear()
            for line in proc.stdout:
                ev = loads(line)
                name = get_name(ev["object"])
                match ev["type"]:
                    case "ADDED" | "MODIFIED": mut_state[name] = ev["object"] #,"kube_context":kube_context,"key":f'{kube_context}~{name}'}
                    case "DELETED": del mut_state[name]
        sleep(2)

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None

def handle_get_state(mut_pods, mut_services, clusters, q):
    mail = q["mail"]
    user_abbr = get_user_abbr(mail)
    pod_name_like = q["podNameLike"]
    pod_name_cond = (
        (lambda v: user_abbr in v) if pod_name_like == "" else
        (lambda v: True) if pod_name_like == "all" else
        (lambda v: 'sp-' in v and 'test' in v) if pod_name_like == 'test' else
        (lambda v: False)
    )
    #pod_re = re.compile(r'(de|sp)-(u|)([^a-z]+)\d*-.+-main-.+')
    fu_name = get_forward_service_name(mail)
    pods = sorted((
        {
            "key": f'{kube_context}~{pod_name}', "kube_context": kube_context, "name": pod_name,
            "status": pod["status"]["phase"],
            "creationTimestamp": pod["metadata"]["creationTimestamp"], #todo may be age on client
            "startedAt": sel(container_status, "state", "running", "startedAt"),
            "restarts": sel(container_status, "restartCount"),
            "selected": selected_app_name and sel(pod,"metadata", "labels", "app") == selected_app_name
        }
        for kube_context, pods in mut_pods.items()
        for selected_app_name in [sel(mut_services[kube_context], fu_name,"spec","selector","app")]
        for pod_name, pod in pods.items() if pod_name_cond(pod_name)
        for container_status in [one_opt(sel(pod,"status", "containerStatuses"))]
        #for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
    ), key=lambda p:p["key"])
    out_msg = { "mail": mail, "userAbbr": user_abbr, "pods": pods, "clusters": clusters }
    return dumps(out_msg, sort_keys=True)

def get_forward_service_name(mail): return f'fu-{get_user_abbr(mail)}'

def handle_select_pod(mut_pods, mail, kube_context, name):
    debug_port = 4005
    pod = mut_pods[kube_context][name]
    app_nm = pod["metadata"]["labels"]["app"]
    manifest = {
        "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(mail) },
        "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
    }
    run((*get_kc(kube_context),"apply","-f-"), text=True, input=dumps(manifest, sort_keys=True))

def handle_recreate_pod(mut_pods, kube_context, name):
    pod = mut_pods[kube_context][name]
    run((*get_kc(kube_context),"delete","pod",get_name(pod)))

def main():
    pub_dir = "/c4/c4pub"
    api_port = 1180
    build_client(pub_dir)
    #
    active_contexts = [c for c in loads(environ["C4KUI_CONTEXTS"]) if c.get("watch")]
    active_cluster_names = {c["cluster"] for c in active_contexts}
    cluster_names = [c["name"] for c in loads(environ["C4KUI_CLUSTERS"])]
    clusters = [{ "name": cn, "watch": cn in active_cluster_names } for cn in cluster_names]
    mut_one_time ={}
    mut_pods = {}
    mut_services = {}
    for kube_context in [c["name"] for c in active_contexts]:
        daemon(kube_watcher, mut_pods.setdefault(kube_context,{}), kube_context, "pods")
        daemon(kube_watcher, mut_services.setdefault(kube_context,{}), kube_context, "services")
    handlers = (
        (True,"GET","",(),lambda a: Path(f'{pub_dir}/index.html').read_bytes().decode()),
        (True,"GET","kop-state",("mail","podNameLike"),lambda a: handle_get_state(mut_pods,mut_services,clusters,a)),
        (True,"GET","ind-login",("name",),lambda a: handle_ind_login(mut_one_time,**a)),
        (True,"GET","ind-auth",("mail","state","code"),lambda a: handle_ind_auth(mut_one_time,**a)),
        (False,"GET","agent-auth",("code",),lambda a: handle_agent_auth(mut_one_time,**a)),
        (True,"POST","kop-select-pod",("mail","kube_context","name"),lambda a: handle_select_pod(mut_pods,**a)),
        (True,"POST","kop-recreate-pod",("kube_context","name"),lambda a: handle_recreate_pod(mut_pods, **a)),
    )
    daemon(run_proxy, api_port, handlers)
    http_serve(("127.0.0.1",api_port), handlers)
