
from subprocess import Popen, PIPE
from json import loads
from os import environ
from time import sleep
from traceback import print_exc

from util import run, dumps

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def get_name(obj): return obj["metadata"]["name"]

def kube_watcher(mut_state, kube_context, kind):
    while True:
        try:
            cmd = (*get_kc(kube_context),"get","--raw",f"/api/v1/namespaces/c4test/{kind}?watch")
            with Popen(cmd, text=True, stdout=PIPE) as proc:
                mut_state.clear()
                for line in proc.stdout:
                    ev = loads(line)
                    name = get_name(ev["object"])
                    match ev["type"]:
                        case "ADDED" | "MODIFIED": mut_state[name] = ev["object"] #,"kube_context":kube_context,"key":f'{kube_context}~{name}'}
                        case "DELETED": mut_state.pop(name, None)
        except Exception:
            print_exc()
        sleep(2)

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])
def one_opt(l): return l[0] if l and len(l)==1 else None

def init_pods(mut_pods, mut_services, active_contexts, get_forward_service_name):
    def get_pods(mail, pod_name_cond):
        return sorted((
            {
                "key": f'{kube_context}~{pod_name}', "kube_context": kube_context, "name": pod_name,
                "status": pod["status"]["phase"],
                "creationTimestamp": pod["metadata"]["creationTimestamp"], #todo may be age on client
                "startedAt": sel(container_status, "state", "running", "startedAt"),
                "restarts": sel(container_status, "restartCount"),
                "selected": selected_app_name and sel(pod,"metadata", "labels", "app") == selected_app_name
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
        app_nm = pod["metadata"]["labels"]["app"]
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
        return lambda: run((*get_kc(kube_context),"scale","--replicas","0","deploy",pod["labels"]["app"]))
    pod_actions = {
        "kop-select-pod": handle_select_pod,
        "kop-recreate-pod": handle_recreate_pod,
        "kop-scale-down": handle_scale_down,
    }
    watchers = [
        d
        for c in active_contexts
        for d in [
            lambda: kube_watcher(mut_pods.setdefault(c["name"],{}), c["name"], "pods"),
            lambda: kube_watcher(mut_services.setdefault(c["name"],{}), c["name"], "services"),
        ]
    ]
    return watchers, get_pods, pod_actions