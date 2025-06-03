from functools import partial
from json import loads
from os import environ
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen
from secrets import token_urlsafe
from base64 import b64encode
from time import monotonic

from util import one, never, log, read_text, dumps

def set_one_time(mut_one_time, key, value): mut_one_time[key] = (monotonic(), value)
def pop_one_time(mut_one_time, key):
    tm, value = mut_one_time.pop(key)
    return value if monotonic() - tm < 30 else never("expired")

def get_redirect_uri(): return f'https://{environ["C4KUI_HOST"]}/ind-auth'

def get_issuer(cluster): return cluster.get("issuer", environ["C4KUI_ISSUER"].replace('{zone}',cluster["zone"]))

def handle_ind_login(mut_one_time,name,location_hash,**_):
    cluster = one(*(c for c in loads(environ["C4KUI_CLUSTERS"]) if c["name"] == name))
    state_key = token_urlsafe(16)
    query_params = {
        "response_type": "code", "client_id": name, "redirect_uri": get_redirect_uri(),
        "scope": "openid profile email offline_access groups", "state": state_key
    }
    set_one_time(mut_one_time, state_key, (cluster, location_hash))
    return f'https://{get_issuer(cluster)}/auth?{urlencode(query_params)}'

def handle_ind_auth(mut_one_time,get_forward_service_name,mail,state,code,**_):
    forward_service_name = get_forward_service_name(mail)
    cluster, location_hash = pop_one_time(mut_one_time,state)
    name = cluster["name"]
    client_secret = loads(read_text(environ["C4KUI_CLIENT_SECRETS"]))[name]
    params = {
        "grant_type": "authorization_code", "code": code, "redirect_uri": get_redirect_uri(),
        "client_id": name, "client_secret": client_secret
    }
    log(f'fetching token for {forward_service_name} / {name}')
    with urlopen(f'https://{get_issuer(cluster)}/token',urlencode(params).encode()) as f:
        f.status == 200 or never(f"bad status: {f.status}")
        msg = loads(f.read().decode())
    log(f'fetched token for {forward_service_name} / {name}')
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
                "--auth-provider-arg", f'idp-issuer-url=https://{get_issuer(cluster)}',
                "--auth-provider-arg", f'client-id={name}',
                "--auth-provider-arg", f'client-secret={client_secret}',
                "--auth-provider-arg", f'refresh-token={msg["refresh_token"]}',
                "--auth-provider-arg", f'id-token={msg["id_token"]}',
            ],
            *(["set-context",c["name"],"--cluster",name,"--user",name,"--namespace",c["ns"]] for c in contexts)
        ],
        "pod_selectors": [f'{c["name"]}~svc~{forward_service_name}' for c in contexts if c.get("watch")],
        "redirect": f'https://{environ["C4KUI_HOST"]}/#{location_hash}',
    }
    a_code = token_urlsafe(16)
    set_one_time(mut_one_time, a_code, out_msg)
    return f'http://localhost:1979/agent-auth?{urlencode({"code":a_code})}'

def handle_agent_auth(mut_one_time,code,**_): return dumps(pop_one_time(mut_one_time,code))

def init_agent_auth(mut_one_time, active_contexts, get_forward_service_name, rt):
    active_cluster_names = {c["cluster"] for c in active_contexts}
    cluster_names = [c["name"] for c in loads(environ["C4KUI_CLUSTERS"])]
    clusters = [{ "name": cn, "watch": cn in active_cluster_names } for cn in cluster_names]
    handlers = {
        "/ind-login": rt.http_auth(partial(handle_ind_login,mut_one_time)),
        "/ind-auth": rt.http_auth(partial(handle_ind_auth, mut_one_time, get_forward_service_name)),
        "/agent-auth": rt.http_no_auth(partial(handle_agent_auth, mut_one_time)),
    }
    return lambda: clusters, handlers