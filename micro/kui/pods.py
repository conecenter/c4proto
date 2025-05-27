
from subprocess import Popen, PIPE
from json import loads
from os import environ

from util import run, dumps, never

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

def init_pods(mut_pods, mut_services, mut_ingresses, active_contexts, get_forward_service_name):
    def get_pods(mail, pod_name_cond):
        return sorted((
            {
                "key": f'{kube_context}~{pod_name}', "kube_context": kube_context, "name": pod_name,
                "status": pod["status"]["phase"],
                "creationTimestamp": pod["metadata"]["creationTimestamp"], #todo may be age on client
                "startedAt": sel(container_status, "state", "running", "startedAt"),
                "restarts": sel(container_status, "restartCount"),
                "image": sel(container_status, "image"),
                "ready": sel(container_status, "ready"),
                "selected": selected_app_name and get_app_name(pod) == selected_app_name,
                "host": sel(one_opt(sel(mut_ingresses[kube_context], get_app_name(pod), "spec", "rules")),"host"),
            }
            for kube_context, pods in mut_pods.items()
            for selected_app_name in [sel(mut_services[kube_context], get_forward_service_name(mail),"spec","selector","app")]
            for pod_name, pod in pods.items() if pod_name_cond(pod_name)
            for container_status in [one_opt(sel(pod,"status", "containerStatuses"))]
            #for m in [pod_re.fullmatch(pod_name)] if m and m.group(3) == user_abbr
        ), key=lambda p:p["key"])
    def handle_select_pod(mail, kube_context, name, **_):
        debug_port = 4005
        pod = mut_pods[kube_context][name]
        app_nm = get_app_name(pod) or never("no app")
        manifest = {
            "kind": "Service", "apiVersion": "v1", "metadata": { "name": get_forward_service_name(mail) },
            "spec": { "ports": [{"port": debug_port}], "selector": {"app": app_nm} }
        }
        return lambda: run((*get_kc(kube_context),"apply","-f-"), text=True, input=dumps(manifest))
    def handle_recreate_pod(kube_context, name, **_):
        pod = mut_pods[kube_context][name]
        return lambda: run((*get_kc(kube_context),"delete","pod",get_name(pod)))
    def handle_scale_down(kube_context, pod_name, **_):
        pod = mut_pods[kube_context][pod_name]
        app_nm = get_app_name(pod) or never("no app")
        return lambda: run((*get_kc(kube_context),"scale","--replicas","0","deploy",app_nm))
    pod_actions = {
        "kop-select-pod": handle_select_pod,
        "kop-recreate-pod": handle_recreate_pod,
        "kop-scale-down": handle_scale_down,
    }
    watchers = [
        d
        for c in active_contexts
        for d in [
            kube_watcher(mut_pods, c, "api/v1", "pods"),
            kube_watcher(mut_services, c, "api/v1", "services"),
            kube_watcher(mut_ingresses, c, "apis/networking.k8s.io/v1", "ingresses"),
        ]
    ]
    return watchers, get_pods, pod_actions