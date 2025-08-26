
from subprocess import Popen, PIPE, check_call, run
from json import loads, dumps
from os import environ
import re

def never(m): raise Exception(m)

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def get_name(obj): return obj["metadata"]["name"]

def kube_watcher(mut_states, context, api, kind):
    kube_context = context["name"]
    mut_state = mut_states.setdefault(kube_context, {})
    def run_loop():
        cmd = (*get_kc(kube_context),"get","--raw",f'/{api}/namespaces/{context["ns"]}/{kind}?watch')
        with Popen(cmd, text=True, stdout=PIPE) as proc:
            mut_state.clear()
            for line in proc.stdout:
                ev = loads(line)
                name = get_name(ev["object"])
                match ev["type"]:
                    case "ADDED" | "MODIFIED": mut_state[name] = ev["object"] #,"kube_context":kube_context,"key":f'{kube_context}~{name}'}
                    case "DELETED": mut_state.pop(name, None)
    return run_loop

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None

def get_app_name(pod): return sel(pod,"metadata", "labels", "app")

def init_pods(mut_pods, mut_services, mut_ingresses, contexts, get_forward_service_name):
    def load(mail, pod_name_like='', pod_list_kube_context='', **_):
        pod_contexts = [c["name"] for c in contexts]
        kube_context = pod_list_kube_context
        if not kube_context or not pod_name_like: return { "need_filters": True, "pod_contexts": pod_contexts }
        cond = re.compile(pod_name_like)
        pods = mut_pods.get(kube_context, [])
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
                "host": sel(one_opt(sel(mut_ingresses[kube_context], get_app_name(pod), "spec", "rules")),"host"),
            }
            for selected_app_name in [sel(mut_services[kube_context], get_forward_service_name(mail),"spec","selector","app")]
            for pod_name, pod in pods.items() if cond.search(pod_name)
            for container_status in [one_opt(sel(pod,"status", "containerStatuses"))]
            #for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
        ), key=lambda p:p["key"])
        return { "items": items, "pod_contexts": pod_contexts }
    def handle_select_pod(mail, kube_context, name, **_):
        debug_port = 4005
        pod = mut_pods[kube_context][name]
        app_nm = get_app_name(pod) or never("no app")
        manifest = dumps({
            "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(mail) },
            "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
        }, sort_keys=True)
        return lambda: run((*get_kc(kube_context),"apply","-f-"), check=True, text=True, input=manifest)
    def handle_recreate_pod(kube_context, name, **_):
        pod = mut_pods[kube_context][name]
        return lambda: check_call((*get_kc(kube_context),"delete","pod",get_name(pod)))
    def handle_scale_down(kube_context, pod_name, **_):
        pod = mut_pods[kube_context][pod_name]
        app_nm = get_app_name(pod) or never("no app")
        return lambda: check_call((*get_kc(kube_context),"scale","--replicas","0","deploy",app_nm))
    pod_actions = {
        "pods.select_pod": handle_select_pod,
        "pods.recreate_pod": handle_recreate_pod,
        "pods.scale_down": handle_scale_down,
        "pods.load": load,
    }
    watchers = [
        d
        for c in contexts
        for d in [
            kube_watcher(mut_pods, c, "api/v1", "pods"),
            kube_watcher(mut_services, c, "api/v1", "services"),
            kube_watcher(mut_ingresses, c, "apis/networking.k8s.io/v1", "ingresses"),
        ]
    ]
    return watchers, pod_actions