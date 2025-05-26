from functools import partial
from json import loads
from os import environ
from re import sub

from servers import daemon, build_client, run_proxy, http_serve, Route
from agent_auth import init_agent_auth
from pods import init_pods

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])
def get_forward_service_name(mail): return f'fu-{get_user_abbr(mail)}'

def handle_get_state(get_clusters, get_pods, mail, processing=None, **q):
    user_abbr = get_user_abbr(mail)
    #
    pod_name_like = q.get("podNameLike","")
    pod_name_cond = (
        (lambda v: user_abbr in v) if pod_name_like == "" else
        (lambda v: True) if pod_name_like == "all" else
        (lambda v: 'sp-' in v and ('test' in v or '-cio-' in v)) if pod_name_like == 'test' else
        (lambda v: False)
    )
    pods = get_pods(mail, pod_name_cond)
    #
    return { "processing": processing, "mail": mail, "userAbbr": user_abbr, "pods": pods, "clusters": get_clusters() }

def main():
    active_contexts = [c for c in loads(environ["C4KUI_CONTEXTS"]) if c.get("watch")]
    mut_one_time = {}
    mut_pods = {}
    mut_services = {}
    mut_tasks = {}
    get_index_content = build_client()
    get_clusters, agent_auth_handlers = init_agent_auth(mut_one_time, active_contexts, get_forward_service_name, Route)
    pod_watchers, get_pods, pod_actions = init_pods(mut_pods, mut_services, active_contexts, get_forward_service_name)
    handlers = {
        **agent_auth_handlers,
        "/": Route.http_auth(lambda **_: get_index_content()),
        "/kop": Route.ws_auth(mut_tasks, {
            **pod_actions,
            "load": partial(handle_get_state,get_clusters,get_pods),
        }),
        "_": Route.http_auth(lambda **_: "404"),
    }
    api_port = 1180
    for pod_watcher in pod_watchers: daemon(pod_watcher)
    daemon(run_proxy, api_port, handlers)
    http_serve(api_port, handlers)
