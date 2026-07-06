from concurrent.futures import ThreadPoolExecutor
from json import loads
from os import environ
from re import sub
from logging import basicConfig, INFO, DEBUG

from util import rget, post, watch
from s3 import init_s3, init_s3bucket
from servers import restarting, run_proxy, http_serve, make_response, make_html_response, flat_exports, init_system_routes
from agent_auth import init_agent_auth, init_clusters
from kube_util import init_kube_resource_watchers
from kube_pods import init_kube_pods
from kube_top import init_kube_top
from cio import init_cio_tasks, init_cio_log_search, init_cio_events, init_cio_log_watchers
from profiling import init_profiling_profiling, init_profiling_thread, init_profiling_logback, init_profiling_gc
from s3_proxy import init_s3_proxy, init_s3client
from allure import init_allure

def get_user_abbr(mail): return sub(r"[^A-Za-z]+","",mail.split("@")[0])
def get_forward_service_name(mail): return f'fu-{get_user_abbr(mail)}'

def init_shared():
    @rget("/links")
    def load_links(): return {
        "cluster_links": [{
            "name": c["name"],
            "grafana": c.get("grafana", environ["C4KUI_GRAFANA"].replace('{zone}',c["zone"])),
        } for c in loads(environ["C4KUI_CLUSTERS"])],
        "custom_links": loads(environ["C4KUI_LINKS"]),
    }
    @post("/refresh")
    def refresh(): pass
    return load_links, refresh

def main():
    #basicConfig(level=DEBUG)
    basicConfig(level= DEBUG if environ.get("C4KUI_DEBUG") else INFO)
    contexts = loads(environ["C4KUI_CONTEXTS"])
    active_contexts = [c for c in contexts if c.get("watch")]
    kcs = { c["name"]: ("kubectl", "--kubeconfig", environ["C4KUBECONFIG"], "--context", c["name"]) for c in contexts }
    mut_resources = {}
    mut_metrics = {}
    mut_cio_proc_by_pid = {} # written by cio_logs watcher, read by cio_tasks load
    mut_log_paths = {}
    s3client = init_s3client()
    api_port = 1180
    m = flat_exports([ # each init_* returns an Export(watchers/actions); merge fails on duplicate keys
        init_agent_auth(get_forward_service_name, make_response), init_clusters(active_contexts),
        init_kube_top(mut_metrics, contexts, kcs),
        init_kube_resource_watchers(mut_resources, contexts, kcs),
        init_kube_pods(mut_resources, mut_metrics, get_forward_service_name, get_user_abbr, kcs),
        init_cio_tasks({}, mut_cio_proc_by_pid, active_contexts, kcs),
        init_cio_log_search(mut_log_paths, active_contexts, make_response),
        init_cio_log_watchers(mut_log_paths, mut_cio_proc_by_pid, active_contexts, kcs),
        init_cio_events({}, active_contexts, kcs),
        init_profiling_profiling(kcs, make_html_response), init_profiling_thread(kcs, make_html_response),
        init_profiling_logback(kcs), init_profiling_gc(kcs),
        init_s3(kcs), init_s3bucket(kcs),
        init_s3_proxy(s3client, make_response),
        init_allure(s3client),
        init_shared(),
        init_system_routes(),
    ])
    executor = ThreadPoolExecutor(max_workers=256)
    restarting(executor, [*m, watch(run_proxy, api_port, m), watch(http_serve, executor, api_port, m)])
