#!/usr/bin/python3 -u
import sys
import subprocess
from json import dumps, load, loads

def log(text): print(text, file=sys.stderr)

def debug_args(hint, args):
    log(f"{hint}: {' '.join(str(a) for a in args)}")
    return args

def run(args, **opt): return subprocess.run(debug_args("running", args), check=True, **opt)

def run_text_out(args, **opt):
    return subprocess.run(debug_args("running", args), check=True, text=True, capture_output=True, **opt).stdout

sys.stdin.reconfigure(encoding='utf-8')
info = load(sys.stdin)
context, c4env, manifests = info["kube-context"], info["c4env"], info["manifests"] # info["state"] ignored
kc = ("kubectl", "--context", context)
env_filter = ("-l", f"c4env={c4env}")
ingress_items = loads(run_text_out((*kc, "get", "ingress", "-o", "json", *env_filter)))["items"]
ingress_types = sorted({f'{m["apiVersion"]}/Ingress' for m in ingress_items})
types = ("/v1/Service", "apps/v1/Deployment", "apps/v1/StatefulSet", *ingress_types)
prune_list = [f"--prune-whitelist={v}" for v in types]
manifests_str = "\n".join(dumps(v) for v in manifests)
run((*kc, "apply", "--prune", *prune_list, *env_filter, "-f-"), text=True, input=manifests_str)
log("** Deployment was applied to cluster. Use other tools to see further progress **")
