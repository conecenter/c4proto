
from functools import partial
from json import loads
from os import environ
from re import sub

from servers import daemon, restarting, build_client, run_proxy, http_serve, Route
from agent_auth import init_agent_auth
from pods import init_pods
from cio import init_cio_tasks

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])
def get_forward_service_name(mail): return f'fu-{get_user_abbr(mail)}'

def handle_get_state(get_clusters, get_pods, get_cio_tasks, mail, processing=None, tab="", **q):
    user_abbr = get_user_abbr(mail)
    match tab:
        case "":
            pod_name_like = q.get("podNameLike","")
            pod_name_cond = (
                (lambda v: user_abbr in v) if pod_name_like == "" else
                (lambda v: True) if pod_name_like == "all" else
                (lambda v: 'sp-' in v and ('test' in v or '-cio-' in v)) if pod_name_like == 'test' else
                (lambda v: False)
            )
            res = { "pods": get_pods(mail, pod_name_cond) }
        case "cio": res = { "cio_tasks": get_cio_tasks() }
        case _: res = {}
    return { "processing": processing, "mail": mail, "userAbbr": user_abbr, "clusters": get_clusters(), **res }

def main():
    active_contexts = [c for c in loads(environ["C4KUI_CONTEXTS"]) if c.get("watch")]
    mut_one_time = {}
    mut_pods = {}
    mut_services = {}
    mut_ingresses = {}
    mut_tasks = {}
    get_index_content = build_client()
    get_clusters, agent_auth_handlers = init_agent_auth(mut_one_time, active_contexts, get_forward_service_name, Route)
    pod_watchers, get_pods, pod_actions = init_pods(
        mut_pods, mut_services, mut_ingresses, active_contexts, get_forward_service_name
    )
    mut_cio_tasks = {}
    cio_watchers, get_cio_tasks = init_cio_tasks(mut_cio_tasks, active_contexts)
    handlers = {
        **agent_auth_handlers,
        "/": Route.http_auth(lambda **_: get_index_content()),
        "/kop": Route.ws_auth(mut_tasks, {
            **pod_actions,
            "load": partial(handle_get_state,get_clusters,get_pods,get_cio_tasks),
        }),
        "_": Route.http_auth(lambda **_: "404"),
    }
    api_port = 1180
    for watcher in [*pod_watchers,*cio_watchers]: daemon(restarting, watcher)
    daemon(run_proxy, api_port, handlers)
    http_serve(api_port, handlers)
