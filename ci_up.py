#!/usr/bin/python3 -u
import sys
import subprocess
from tempfile import TemporaryDirectory
from pathlib import Path
from json import dumps, load
sys.stdin.reconfigure(encoding='utf-8')
info = load(sys.stdin)
tmp = TemporaryDirectory()
chart = {"apiVersion": "v2", "name": info["state"], "version": "0"}
Path(f"{tmp.name}/templates").mkdir()
Path(f"{tmp.name}/Chart.yaml").write_text(dumps(chart, sort_keys=True), encoding="utf-8", errors="strict")
Path(f"{tmp.name}/templates/identity.yaml").write_bytes(b"{{range .Values.manifests}}\n---\n{{toYaml .}}{{end}}")
name, = {man["metadata"]["labels"]["c4env"] for man in info["manifests"]}
cmd = ("helm", "upgrade", "--install", "--wait", "--kube-context", "--atomic", info["context"], name, tmp.name, "-f-")
# --atomic to avoid manual rollbacks
print("running: " + " ".join(cmd), file=sys.stderr)
subprocess.run(cmd, check=True, text=True, input=dumps(info, sort_keys=True, indent=4))
