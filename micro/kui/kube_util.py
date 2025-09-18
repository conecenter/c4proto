
from subprocess import Popen, PIPE
from json import loads
from functools import partial

def init_kube_resource_watchers(mut_resources, contexts, kcp):
    def watcher(context, api, kind):
        kube_context = context["name"]
        cmd = (*kcp,kube_context,"get","--raw",f'/{api}/{kind}?watch')
        with Popen(cmd, text=True, stdout=PIPE) as proc:
            mut_state = {}
            mut_resources[(kind, kube_context)] = mut_state
            for line in proc.stdout:
                ev = loads(line)
                name = ev["object"]["metadata"]["name"]
                match ev["type"]:
                    case "ADDED" | "MODIFIED": mut_state[name] = ev["object"] #,"kube_context":kube_context,"key":f'{kube_context}~{name}'}
                    case "DELETED": mut_state.pop(name, None)
    return [
        d
        for c in contexts
        for d in [
            #partial(watcher, c, "api/v1", "nodes"),
            partial(watcher, c, f"api/v1/namespaces/{c["ns"]}", "pods"),
            partial(watcher, c, f"api/v1/namespaces/{c["ns"]}", "services"),
            partial(watcher, c, f"apis/networking.k8s.io/v1/namespaces/{c["ns"]}", "ingresses"),
        ]
    ]
