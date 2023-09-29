
import subprocess
import random
import pathlib
import sys
import json


def run(args, **opt):
    print("running: " + " ".join(args), file=sys.stderr)
    return subprocess.run(args, check=True, **opt)


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
pathlib.Path(tmp/"snapshot_name").write_bytes(snapshot_name.encode('utf-8'))
snapshot_get_cmd = (*kc, "exec", s3pod, "--", "/tools/mc", "cat", f"def/{inbox}.snapshots/{snapshot_name}")
pathlib.Path(tmp/"snapshots"/snapshot_name).write_bytes(run(snapshot_get_cmd, capture_output=True).stdout)
ls_txs_cmd = (*kc, "exec", topic_pod, "--", "ls", "/tmp/snapshot_txs")
txs = sorted(tx for tx in run(ls_txs_cmd, capture_output=True, text=True).stdout.splitlines() if tx >= snapshot_name) #>=?
txs_bytes = "\n".join(txs[:conf["tx_count"]]).encode('utf-8')
pathlib.Path(tmp/"snapshot_tx_list").write_bytes(txs_bytes)
txs_get_cmd = (*kc, "exec", "-i", topic_pod, "--", "tar", "-C", "/tmp/snapshot_txs", "-cf-", "-T-")
pathlib.Path(tmp/"snapshots_txs.tar").write_bytes(run(txs_get_cmd, capture_output=True, input=txs_bytes).stdout)
(tmp/"snapshots_txs").mkdir(parents=True)
run(("tar", "-C", str(tmp/"snapshots_txs"), "-xf", str(tmp/"snapshots_txs.tar")))
