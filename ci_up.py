#!/usr/bin/python3 -u
import sys
import subprocess
from json import dumps, load
sys.stdin.reconfigure(encoding='utf-8')
info = load(sys.stdin)
context, c4env, manifests = info["kube-context"], info["c4env"], info["manifests"] # info["state"] ignored
prune_list = [f"--prune-whitelist={v}" for v in (
    "/v1/Service", "apps/v1/Deployment", "apps/v1/StatefulSet",
    "networking.k8s.io/v1/Ingress", "extensions/v1beta1/Ingress"
)]
cmd = ("kubectl", "--context", context, "apply", "--prune", *prune_list, "-l", f'c4env={c4env}', "-f-")
print("running: " + " ".join(cmd), file=sys.stderr)
subprocess.run(cmd, check=True, text=True, input="\n".join(dumps(v) for v in manifests))
print("** Deployment was applied to cluster. Use other tools to see further progress **", file=sys.stderr)
