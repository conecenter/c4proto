from subprocess import check_output
from json import dumps
import re
from time import time

from util import grouped, die, rget, post

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None

def get_app_name(pod): return sel(pod,"metadata", "labels", "app")

def get_env_value(pod, name):
    for container in sel(pod, "spec", "containers") or []:
        for env in container.get("env") or []:
            if env.get("name") == name:
                return env.get("value")
    return None

def init_kube_pods(mut_resources, mut_metrics, get_forward_service_name, get_user_abbr, kcs):
    @rget("/pods")
    def load(mail, pod_name_like, kube_context):
        pod_contexts = sorted(kcs)
        if not kube_context or not pod_name_like:
            return { "need_filters": True, "pod_contexts": pod_contexts, "user_abbr": get_user_abbr(mail) }
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
                "node_name": sel(pod, "spec", "nodeName"),
                "name": pod_name,
                "app_name": get_app_name(pod),
                "status": pod["status"]["phase"],
                "creation_timestamp": pod["metadata"]["creationTimestamp"], #todo may be age on client
                "started_at": sel(container_status, "state", "running", "startedAt"),
                "restarts": sel(container_status, "restartCount"),
                "image": sel(container_status, "image"),
                "ready": sel(container_status, "ready"),
                "selected": selected_app_name and get_app_name(pod) == selected_app_name,
                "host": sel(one_opt(sel(mut_resources, ("ingresses", kube_context), get_app_name(pod), "spec", "rules")),"host"),
                "usage_cpu": sel(usage, "cpu"),
                "usage_memory": sel(usage, "memory"),
                "inbox_bucket": f'{inbox_prefix}.snapshots' if inbox_prefix else None,
            }
            for selected_app_name in [sel(mut_resources, ("services", kube_context), get_forward_service_name(mail),"spec","selector","app")]
            for pod_name, pod in (sel(mut_resources, ("pods", kube_context)) or {}).items() if cond.search(pod_name)
            for container_status in [one_opt(sel(pod,"status", "containerStatuses"))]
            for usage in [one_opt(usage_by_pod.get(pod_name))]
            for inbox_prefix in [get_env_value(pod, "C4INBOX_TOPIC_PREFIX")]
            #for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
        ), key=lambda p:p["key"])
        return { "items": items, "pod_contexts": pod_contexts, "user_abbr": get_user_abbr(mail) }
    def check_pod(kube_context, pod_name):
        return mut_resources[("pods", kube_context)][pod_name]
    @post("/pods.select_pod")
    def select_pod(mail, kube_context, name):
        debug_port = 4005
        pod = check_pod(kube_context, name)
        app_nm = get_app_name(pod) or die("no app")
        manifest = dumps({
            "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(mail) },
            "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
        }, sort_keys=True)
        check_output((*kcs[kube_context],"apply","-f-"), input=manifest.encode())
    @post("/pods.recreate_pod")
    def recreate_pod(kube_context, name):
        pod = check_pod(kube_context, name)
        check_output((*kcs[kube_context],"delete","pod",pod["metadata"]["name"]))
    @post("/pods.scale_down")
    def scale_down(kube_context, pod_name):
        pod = check_pod(kube_context, pod_name)
        app_nm = get_app_name(pod) or die("no app")
        check_output((*kcs[kube_context],"scale","--replicas","0","deploy",app_nm))
    return load, select_pod, recreate_pod, scale_down
