
import subprocess
import random
import pathlib
import sys
import json


def run(args, **opt):
    print("running: " + " ".join(args), file=sys.stderr)
    return subprocess.run(args, check=True, **opt)


def offs(nm): return nm.split("-")[0]


conf = json.load(sys.stdin)
inbox = conf["inbox"]
snapshot_name = conf["snapshot_name"]
topic_pod = conf["topic_pod"]
tmp = pathlib.Path(f"/tmp/{random.random()}")
print(tmp)
kc = ("kubectl", "--context", conf["kube_context"])
find_s3_cmd = (*kc, "get", "pods", "-o", "json", "-l", f"c4s3client")
s3pod = json.loads(run(find_s3_cmd, capture_output=True, text=True).stdout)["items"][0]["metadata"]["name"]
(tmp/"snapshots").mkdir(parents=True)
pathlib.Path(tmp/"snapshot_name").write_bytes(f"snapshots/{snapshot_name}".encode('utf-8'))
snapshot_get_cmd = (*kc, "exec", s3pod, "--", "/tools/mc", "cat", f"def/{inbox}.snapshots/{snapshot_name}")
pathlib.Path(tmp/"snapshots"/snapshot_name).write_bytes(run(snapshot_get_cmd, capture_output=True).stdout)
ls_txs_cmd = (*kc, "exec", topic_pod, "--", "ls", "/tmp/snapshot_txs")
snap_offs = offs(snapshot_name)
txs = sorted(tx for tx in run(ls_txs_cmd, capture_output=True, text=True).stdout.splitlines() if offs(tx) > snap_offs)
txs_bytes = "\n".join(f"snapshot_txs/{tx}" for tx in txs[:conf["tx_count"]]).encode('utf-8')
pathlib.Path(tmp/"snapshot_tx_list").write_bytes(txs_bytes)
txs_get_cmd = (*kc, "exec", "-i", topic_pod, "--", "tar", "-C", "/tmp", "-cf-", "-T-")
pathlib.Path(tmp/"snapshot_txs.tar").write_bytes(run(txs_get_cmd, capture_output=True, input=txs_bytes).stdout)
run(("tar", "-C", str(tmp), "-xf", str(tmp/"snapshot_txs.tar")))
