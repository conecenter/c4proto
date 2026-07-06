from subprocess import Popen, PIPE
from json import loads

from util import watch

def init_kube_resource_watchers(mut_resources, contexts, kcs):
    def watcher(context, api, kind):
        kube_context = context["name"]
        cmd = (*kcs[kube_context],"get","--raw",f'/{api}/{kind}?watch')
        with Popen(cmd, stdout=PIPE) as proc:
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
            watch(watcher, c, f"api/v1/namespaces/{c["ns"]}", "pods"),
            watch(watcher, c, f"api/v1/namespaces/{c["ns"]}", "services"),
            watch(watcher, c, f"apis/networking.k8s.io/v1/namespaces/{c["ns"]}", "ingresses"),
        ]
    ]
