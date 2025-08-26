
from json import loads
from os import environ
from re import sub
from tempfile import TemporaryDirectory
from pathlib import Path
from logging import basicConfig, INFO, DEBUG
from concurrent.futures import ThreadPoolExecutor

from s3 import init_s3
from servers import daemon, restarting, build_client, run_proxy, http_serve, Route
from agent_auth import init_agent_auth
from pods import init_pods
from cio import init_cio_tasks, init_cio_logs

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])
def get_forward_service_name(mail): return f'fu-{get_user_abbr(mail)}'

def load_links(**_): return {
    "cluster_links": [{
        "name": c["name"],
        "grafana": c.get("grafana", environ["C4KUI_GRAFANA"].replace('{zone}',c["zone"])),
    } for c in loads(environ["C4KUI_CLUSTERS"])],
    "custom_links": loads(environ["C4KUI_LINKS"]),
}

def main():
    basicConfig(level= DEBUG if environ.get("C4KUI_DEBUG") else INFO)
    dir_life = TemporaryDirectory()
    contexts = loads(environ["C4KUI_CONTEXTS"])
    active_contexts = [c for c in contexts if c.get("watch")]
    index_content, app_ver = build_client()
    get_clusters, agent_auth_handlers = init_agent_auth({}, active_contexts, get_forward_service_name, Route)
    def load_shared(mail): return {
        "appVersion": app_ver, "userAbbr": get_user_abbr(mail), "clusters": get_clusters(),
        "managedKubeContexts": [c["name"] for c in active_contexts],
    }
    pod_watchers, pod_actions = init_pods({}, {}, {}, contexts, get_forward_service_name)
    cio_task_watchers, cio_task_actions = init_cio_tasks({}, active_contexts)
    cio_log_watchers, cio_log_actions, cio_log_handlers = init_cio_logs(Path(dir_life.name), active_contexts, get_user_abbr, Route)
    executor = ThreadPoolExecutor(max_workers=16)
    s3_actions = init_s3(executor)
    handlers = {
        **agent_auth_handlers, **cio_log_handlers,
        "/": Route.http_auth(lambda **_: index_content),
        "/kop": Route.ws_auth({}, load_shared, {
            **pod_actions, **cio_task_actions, **cio_log_actions, **s3_actions, "links.load": load_links,
        }),
        "_": Route.http_auth(lambda **_: "404"),
    }
    api_port = 1180
    for watcher in [*pod_watchers,*cio_task_watchers,*cio_log_watchers]: daemon(restarting, watcher)
    daemon(run_proxy, api_port, handlers)
    http_serve(api_port, handlers)
