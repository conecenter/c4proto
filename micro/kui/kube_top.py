from subprocess import check_output
from json import loads
from time import time, sleep

from util import watch

def refresher(mut_states, kcs, kube_context, url, key):
    loaded = 0
    while True:
        need_reload = (loaded or 0) < mut_states.get(("expired", kube_context), 0)
        if need_reload:
            items = loads(check_output((*kcs[kube_context], "get", "--raw", url), timeout=5))["items"]
            mut_states[(key, kube_context)] = items
            loaded = time()
        sleep(1)

def init_kube_top(mut_metrics, contexts, kcs):
    return [
        watch(refresher, mut_metrics, kcs, c["name"], f"/apis/metrics.k8s.io/v1beta1/namespaces/{c["ns"]}/pods", "pod_metrics")
        for c in contexts
    ]
