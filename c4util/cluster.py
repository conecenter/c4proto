
import json
import base64
import os

from . import run_text_out


def get_secret_data(kc, secret_name):
    secret = json.loads(run_text_out((*kc, "get", "secret", secret_name, "-o", "json")))
    return lambda secret_fn: base64.b64decode(secret["data"][secret_fn])


def get_kubectl(kube_context): return "kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "--context", kube_context


def s3path(path): return f"def/{path}"


def s3init(kc):
    s3pod = max(run_text_out((*kc, "get", "pods", "-o", "name", "-l", "c4s3client")).splitlines())
    return *kc, "exec", "-i", s3pod, "--", "/tools/mc"


def s3list(mc, bucket): return [json.loads(line) for line in run_text_out((*mc, "ls", "--json", bucket)).splitlines()]


def get_env_values_from_pods(env_key, pods):
    return {e["value"] for p in pods for c in p["spec"]["containers"] for e in c.get("env", []) if e["name"] == env_key}
