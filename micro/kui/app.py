
from functools import partial
from json import loads
from os import environ
from re import sub
from time import monotonic

from servers import daemon, restarting, build_client, run_proxy, http_serve, Route
from agent_auth import init_agent_auth
from pods import init_pods
from cio import init_cio_tasks, init_cio_logs

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])
def get_forward_service_name(mail): return f'fu-{get_user_abbr(mail)}'

def handle_get_state(get_clusters, get_pods, get_cio_tasks, get_cio_logs, mail, tab="", **q):
    res = { "userAbbr": get_user_abbr(mail), "clusters": get_clusters() }
    match tab:
        case "": return { **res, "pods": get_pods(mail, q.get("podNameLike","")) }
        case "cio_tasks": return { **res, "cio_tasks": get_cio_tasks() }
        case "cio_logs": return { **res, "cio_tasks": get_cio_logs() }
        case _: return {}

def main():
    active_contexts = [c for c in loads(environ["C4KUI_CONTEXTS"]) if c.get("watch")]
    mut_one_time = {}
    mut_pods = {}
    mut_services = {}
    mut_ingresses = {}
    mut_tasks = {}
    index_content, app_ver = build_client()
    get_clusters, agent_auth_handlers = init_agent_auth(mut_one_time, active_contexts, get_forward_service_name, Route)
    pod_watchers, get_pods, pod_actions = init_pods(
        mut_pods, mut_services, mut_ingresses, active_contexts, get_forward_service_name
    )
    mut_cio_tasks = {}
    cio_task_watchers, get_cio_tasks = init_cio_tasks(mut_cio_tasks, active_contexts)
    mut_cio_logs = {}
    cio_log_watchers, get_cio_logs = init_cio_logs(mut_cio_logs, active_contexts)
    handlers = {
        **agent_auth_handlers,
        "/": Route.http_auth(lambda **_: index_content),
        "/kop": Route.ws_auth(mut_tasks, app_ver, {
            **pod_actions,
            "load": partial(handle_get_state,get_clusters,get_pods,get_cio_tasks,get_cio_logs),
        }),
        "_": Route.http_auth(lambda **_: "404"),
    }
    api_port = 1180
    for watcher in [*pod_watchers,*cio_task_watchers,*cio_log_watchers]: daemon(restarting, watcher)
    daemon(run_proxy, api_port, handlers)
    http_serve(api_port, handlers)
