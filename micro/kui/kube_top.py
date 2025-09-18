from subprocess import check_output
from json import loads
from time import time, sleep
from os import environ
from functools import partial
from itertools import groupby

def sel(v, *path):
    return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def refresher(mut_states, kcp, context, url, key):
    kube_context = context["name"]
    loaded = 0
    while True:
        need_reload = (loaded or 0) < mut_states.get(("expired", kube_context), 0)
        if need_reload:
            items = loads(check_output((*kcp, kube_context, "get", "--raw", url), timeout=5))["items"]
            mut_states[(key, kube_context)] = items
            loaded = time()

def init_kube_top(mut_metrics, mut_resources, contexts, kcp):
    def load_top(top_kube_context='', **_):
        top_contexts = [c["name"] for c in contexts]
        if top_kube_context not in top_contexts:
            return { "top_contexts": top_contexts, "need_filters": True }
        mut_metrics[("expired", top_kube_context)] = time() - 15 # Trigger metrics fetch by setting observed_at
        containers = sorted((
            {
                "node_name": sel(mut_resources, ("pods", top_kube_context), pod_name, "spec", "nodeName") or "-",
                "pod_name": pod_name,
                "container_name": cm["name"],
                "usage_memory": sel(cm, "usage", "memory") or "-",
                "usage_cpu": sel(cm, "usage", "cpu") or "-",
            }
            for pod_metrics in mut_metrics.get(("pod_metrics", top_kube_context)) or []
            for pod_name in [pod_metrics["metadata"]["name"]]
            for cm in sel(pod_metrics, "containers") or []
        ), key=lambda o: (o["node_name"], o["pod_name"], o["container_name"]))
        items = [
            { "name": node_name, "containers": [*containers] }
            for node_name, containers in groupby(containers, lambda o: o["node_name"])
        ]
        return { "top_contexts": top_contexts, "items": items }
    top_actions = { "top.load": load_top }
    watchers = [
        partial(refresher, mut_metrics, kcp, c, f"/apis/metrics.k8s.io/v1beta1/namespaces/{c["ns"]}/pods", "pod_metrics")
        for c in contexts
    ]
    return watchers, top_actions
