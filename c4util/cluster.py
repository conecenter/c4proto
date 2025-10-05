
import json
import base64
import os
from time import sleep
from socket import create_connection
from typing import Tuple
from functools import partial
from traceback import print_exc
from queue import Queue

from . import run_text_out, repeat, never


def get_secret_data(kc, secret_name):
    secret = json.loads(run_text_out((*kc, "get", "secret", secret_name, "-o", "json")))
    return lambda secret_fn: base64.b64decode(secret["data"][secret_fn])


def get_secret_part(kc, k8s_path):
    secret_name, secret_fn = k8s_path.split("/")
    return get_secret_data(kc, secret_name)(secret_fn)


def get_kubectl(kube_context): return "kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "--context", kube_context


def s3path(path): return f"def/{path}"


def s3init(kc): return *kc, "exec", "-i", "svc/c4s3client", "--", "/tools/mc"


def s3list(mc, bucket): return [json.loads(line) for line in run_text_out((*mc, "ls", "--json", bucket)).splitlines()]


def get_prefixes_from_pods(pods): return get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", pods)


def get_env_values_from_pods(env_key, pods):
    return {e["value"] for p in pods for c in p["spec"]["containers"] for e in c.get("env", []) if e["name"] == env_key}


def get_all_contexts():
    cmd = ("kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "config", "get-contexts", "-o", "name")
    return run_text_out(cmd).splitlines()


def get_pods_json(kc, add):
    return json.loads(run_text_out((*kc, "get", "pods", "-o", "json", *add), timeout=8))["items"]


def wait_no_active_prefix(kc, prefix):
    repeat(lambda: sleep(2) if prefix in get_prefixes_from_pods(get_pods_json(kc, ())) else True, (True,))


###

def restarting(f,*args):
    while True:
        try: f(*args)
        except Exception: print_exc()
        sleep(2)

def sock_exchange_init(sock):
    file = sock.makefile('r', encoding='utf-8', newline='\n')
    def exchange(msg: bytes, need_resp: str):
        sock.sendall(msg + b'\n')
        resp_tp, *resp_args = file.readline().split()
        return resp_args if resp_tp == need_resp else never(f"bad resp: {resp_tp} {resp_args}")
    return exchange

def init_kafka_producer(host_port: Tuple[str, int], topic: str):
    def run():
        with create_connection(host_port) as sock:
            exchange = sock_exchange_init(sock)
            exchange(f'PRODUCE {topic}'.encode(), "OK")
            while True:
                if not pending_q: pending_q.append(from_q.get())
                exchange(pending_q[0], "ACK")
                pending_q.pop(0)
    from_q = Queue()
    pending_q = []
    return partial(restarting, run), from_q.put
