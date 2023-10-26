
import subprocess
import random
import pathlib
import sys
import json
from argparse import ArgumentParser

def run(args, **opt):
    print("running: " + " ".join(args), file=sys.stderr)
    return subprocess.run(args, check=True, **opt)


def offs(nm): return nm.split("-")[0]


def find_pods(kube_context, label):
    cmd = ("kubectl", "--context", kube_context, "get", "pods", "-o", "json", "-l", label)
    return json.loads(run(cmd, capture_output=True, text=True).stdout)["items"]


def get_name(pod): return pod["metadata"]["name"]


parser = ArgumentParser()
parser.add_argument("--kube-context", required=True)
parser.add_argument("--app", required=True)
parser.add_argument("--snapshot-name", required=True)
parser.add_argument("--tx-count", required=True)
opt = parser.parse_args()
kube_context = opt.kube_context
snapshot_name = opt.snapshot_name
tmp = pathlib.Path(f"/tmp/{random.random()}")
print(tmp)
kc = ("kubectl", "--context", kube_context)
s3pod_list = find_pods(kube_context, "c4s3client")
s3pod = get_name(s3pod_list[0])
(tmp/"snapshots").mkdir(parents=True)
pathlib.Path(tmp/"snapshot_name").write_bytes(f"snapshots/{snapshot_name}".encode('utf-8'))
topic_pod_st, = find_pods(kube_context, f"app={opt.app}")
topic_pod = get_name(topic_pod_st)
inbox, = [e["value"] for c in topic_pod_st["spec"]["containers"] for e in c["env"] if e["name"]=="C4INBOX_TOPIC_PREFIX"]
snapshot_get_cmd = (*kc, "exec", s3pod, "--", "/tools/mc", "cat", f"def/{inbox}.snapshots/{snapshot_name}")
pathlib.Path(tmp/"snapshots"/snapshot_name).write_bytes(run(snapshot_get_cmd, capture_output=True).stdout)
ls_txs_cmd = (*kc, "exec", topic_pod, "--", "ls", "/tmp/snapshot_txs")
snap_offs = offs(snapshot_name)
txs = sorted(tx for tx in run(ls_txs_cmd, capture_output=True, text=True).stdout.splitlines() if offs(tx) > snap_offs)
txs_bytes = "\n".join(f"snapshot_txs/{tx}" for tx in txs[:int(opt.tx_count)]).encode('utf-8')
pathlib.Path(tmp/"snapshot_tx_list").write_bytes(txs_bytes)
txs_get_cmd = (*kc, "exec", "-i", topic_pod, "--", "tar", "-C", "/tmp", "-cf-", "-T-")
pathlib.Path(tmp/"snapshot_txs.tar").write_bytes(run(txs_get_cmd, capture_output=True, input=txs_bytes).stdout)
run(("tar", "-C", str(tmp), "-xf", str(tmp/"snapshot_txs.tar")))
