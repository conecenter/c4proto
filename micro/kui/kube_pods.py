
from subprocess import check_call, run
from json import dumps
from os import environ
import re
from itertools import groupby
from time import time

def never(m): raise Exception(m)

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None

def grouped(l): return [(k,[v for _,v in kvs]) for k,kvs in groupby(sorted(l, key=lambda kv: kv[0]), lambda kv: kv[0])]

def get_app_name(pod): return sel(pod,"metadata", "labels", "app")

def init_kube_pods(mut_resources, mut_metrics, contexts, get_forward_service_name, kcp):
    def load(mail, pod_name_like='', pod_list_kube_context='', **_):
        pod_contexts = [c["name"] for c in contexts]
        kube_context = pod_list_kube_context
        if not kube_context or not pod_name_like: return { "need_filters": True, "pod_contexts": pod_contexts }
        cond = re.compile(pod_name_like)
        mut_metrics[("expired", kube_context)] = time() - 15 # Trigger metrics fetch
        usage_by_pod = dict(grouped(
            (pod_metrics["metadata"]["name"], sel(cm, "usage"))
            for pod_metrics in mut_metrics.get(("pod_metrics", kube_context)) or []
            for cm in sel(pod_metrics, "containers") or [] if cm["name"] == "main"
        ))
        items = sorted((
            {
                "key": f'{kube_context}~{pod_name}',
                "kube_context": kube_context,
                "nodeName": sel(pod, "spec", "nodeName"),
                "name": pod_name,
                "appName": get_app_name(pod),
                "status": pod["status"]["phase"],
                "creationTimestamp": pod["metadata"]["creationTimestamp"], #todo may be age on client
                "startedAt": sel(container_status, "state", "running", "startedAt"),
                "restarts": sel(container_status, "restartCount"),
                "image": sel(container_status, "image"),
                "ready": sel(container_status, "ready"),
                "selected": selected_app_name and get_app_name(pod) == selected_app_name,
                "host": sel(one_opt(sel(mut_resources, ("ingresses", kube_context), get_app_name(pod), "spec", "rules")),"host"),
                "usage_cpu": sel(usage, "cpu"),
                "usage_memory": sel(usage, "memory"),
            }
            for selected_app_name in [sel(mut_resources, ("services", kube_context), get_forward_service_name(mail),"spec","selector","app")]
            for pod_name, pod in (sel(mut_resources, ("pods", kube_context)) or {}).items() if cond.search(pod_name)
            for container_status in [one_opt(sel(pod,"status", "containerStatuses"))]
            for usage in [one_opt(usage_by_pod.get(pod_name))]
            #for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
        ), key=lambda p:p["key"])
        return { "items": items, "pod_contexts": pod_contexts }
    def check_pod(kube_context, pod_name):
        return mut_resources[("pods", kube_context)][pod_name]
    def handle_select_pod(mail, kube_context, name, **_):
        debug_port = 4005
        pod = check_pod(kube_context, name)
        app_nm = get_app_name(pod) or never("no app")
        manifest = dumps({
            "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(mail) },
            "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
        }, sort_keys=True)
        return lambda: run((*kcp,kube_context,"apply","-f-"), check=True, text=True, input=manifest)
    def handle_recreate_pod(kube_context, name, **_):
        pod = check_pod(kube_context, name)
        return lambda: check_call((*kcp,kube_context,"delete","pod",pod["metadata"]["name"]))
    def handle_scale_down(kube_context, pod_name, **_):
        pod = check_pod(kube_context, pod_name)
        app_nm = get_app_name(pod) or never("no app")
        return lambda: check_call((*kcp,kube_context,"scale","--replicas","0","deploy",app_nm))
    pod_actions = {
        "pods.select_pod": handle_select_pod,
        "pods.recreate_pod": handle_recreate_pod,
        "pods.scale_down": handle_scale_down,
        "pods.load": load,
    }
    return pod_actions