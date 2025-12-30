
### cof - compiler factory - creates pairing `c4de-` for each `de-`

from json import loads, dumps
from os import environ
from subprocess import check_output
from sys import stderr
from time import sleep
from traceback import print_exc

def da(*args):
    print(f"starting: {' '.join(str(a) for a in args)}", file=stderr)
    return args

def get_pods(kc, from_prefix, to_prefix):
    pods = {
        f'{to_prefix}{rt}': p["spec"]
        for p in loads(check_output(da(*kc, "get", "pods", "-o", "json")))["items"]
        for lt, spl, rt in [p["metadata"]["name"].partition(from_prefix)] if lt == "" and spl == from_prefix
    }
    return {*pods.keys()}, pods

def construct_pod(name, fr_spec): return {
    "apiVersion": "v1", "kind": "Pod", "metadata": { "name": name },
    "spec": {
        "containers": [{
            "name": "main", "command": ["sleep", "infinity"],
            "image": c["image"], "securityContext": c["securityContext"],
        } for c in fr_spec["containers"]],
        "securityContext": fr_spec["securityContext"],
        "imagePullSecrets": [{ "name": "c4pull" }],
        "nodeSelector": { "c4builder": "true" },
        "tolerations": [{ "key": "c4builder", "operator": "Exists", "effect": "NoSchedule" }],
    }
}

def iteration(conf_path, from_kube_context, to_kube_context):
    kcp = ("kubectl", "--kubeconfig", conf_path, "--context")
    to_prefix = "c4de-"
    from_pod_set, from_pods = get_pods((*kcp, from_kube_context), "de-", to_prefix)
    to_pod_set, _ = get_pods((*kcp, to_kube_context), to_prefix, to_prefix)
    to_del = sorted(to_pod_set - from_pod_set)
    if to_del: check_output(da(*kcp, to_kube_context, "delete", "pods", *to_del))
    to_add = [construct_pod(nm, from_pods[nm]) for nm in sorted(from_pod_set - to_pod_set)]
    to_add_str = "\n".join(dumps(v, sort_keys=True) for v in to_add)
    if to_add_str: check_output(da(*kcp, to_kube_context, "apply", "-f-"), input=to_add_str.encode())

while True:
    try: iteration(environ["C4KUBECONFIG"], environ["C4COF_FROM"], environ["C4COF_TO"])
    except: print_exc()
    sleep(30)
