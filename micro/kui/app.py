
from json import loads
from os import environ
from re import sub
from tempfile import TemporaryDirectory
from pathlib import Path
from logging import basicConfig, INFO, DEBUG

from s3 import init_s3, init_s3bucket
from servers import daemon, restarting, build_client, run_proxy, http_serve, Route
from agent_auth import init_agent_auth
from kube_util import init_kube_resource_watchers
from kube_pods import init_kube_pods
from kube_top import init_kube_top
from cio import init_cio_tasks, init_cio_logs, init_cio_events
from profiling import init_profiling

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
    # kube
    kcp = ("kubectl", "--kubeconfig", environ["C4KUBECONFIG"], "--context")
    mut_resources = {}
    mut_metrics = {}
    kube_resource_watchers = init_kube_resource_watchers(mut_resources, contexts, kcp)
    kube_pod_actions = init_kube_pods(mut_resources, mut_metrics, contexts, get_forward_service_name, kcp)
    kube_top_watchers = init_kube_top(mut_metrics, contexts, kcp)
    kube_watchers = [*kube_resource_watchers, *kube_top_watchers]
    kube_actions = {**kube_pod_actions}
    # cio
    mut_cio_tasks = {}
    mut_cio_proc_by_pid = {}
    cio_task_watchers, cio_task_actions = init_cio_tasks(mut_cio_tasks, mut_cio_proc_by_pid, active_contexts, kcp)
    cio_log_watchers, cio_log_actions, cio_log_handlers = init_cio_logs(
        Path(dir_life.name), active_contexts, get_user_abbr, Route, kcp, mut_cio_proc_by_pid
    )
    cio_event_watchers, cio_event_actions = init_cio_events({}, active_contexts, kcp)
    #
    profiling_actions, profiling_handlers = init_profiling({}, contexts, Route, kcp)
    s3_actions = init_s3(contexts, kcp)
    s3bucket_actions, s3bucket_watcher = init_s3bucket(contexts, kcp)
    handlers = {
        **agent_auth_handlers, **cio_log_handlers, **profiling_handlers,
        "/": Route.http_auth(lambda **_: index_content),
        "/kop": Route.ws_auth({}, load_shared, {
            **kube_actions, **cio_task_actions, **cio_log_actions, **cio_event_actions, **profiling_actions, **s3_actions, **s3bucket_actions,
            "links.load": load_links,
        }),
        "_": Route.http_auth(lambda **_: "404"),
    }
    api_port = 1180
    for watcher in [*kube_watchers,*cio_task_watchers,*cio_log_watchers,*cio_event_watchers, s3bucket_watcher]: daemon(restarting, watcher)
    daemon(run_proxy, api_port, handlers)
    http_serve(api_port, handlers)
