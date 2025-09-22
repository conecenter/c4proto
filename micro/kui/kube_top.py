from subprocess import check_output
from json import loads
from time import time, sleep
from os import environ
from functools import partial
from itertools import groupby

def refresher(mut_states, kcp, context, url, key):
    kube_context = context["name"]
    loaded = 0
    while True:
        need_reload = (loaded or 0) < mut_states.get(("expired", kube_context), 0)
        if need_reload:
            items = loads(check_output((*kcp, kube_context, "get", "--raw", url), timeout=5))["items"]
            mut_states[(key, kube_context)] = items
            loaded = time()
        sleep(1)

def init_kube_top(mut_metrics, contexts, kcp):
    return [
        partial(refresher, mut_metrics, kcp, c, f"/apis/metrics.k8s.io/v1beta1/namespaces/{c["ns"]}/pods", "pod_metrics")
        for c in contexts
    ]
