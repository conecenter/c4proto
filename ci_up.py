#!/usr/bin/python3 -u
import sys
import subprocess
from tempfile import TemporaryDirectory
from pathlib import Path
from json import dumps, load
sys.stdin.reconfigure(encoding='utf-8')
info = load(sys.stdin)
context, release, state = info["kube-context"], info["c4env"], info["state"]
chart_dir = TemporaryDirectory()
chart = {"apiVersion": "v2", "name": f"{release}.{state}", "version": "0"}
Path(f"{chart_dir.name}/templates").mkdir()
Path(f"{chart_dir.name}/Chart.yaml").write_text(dumps(chart, sort_keys=True), encoding="utf-8", errors="strict")
Path(f"{chart_dir.name}/templates/identity.yaml").write_bytes(b"{{range .Values.manifests}}\n---\n{{toYaml .}}{{end}}")
flags = ("--install", "--wait", "--atomic", "--timeout", "15m", "--kube-context", context)
cmd = ("helm", "upgrade", *flags, release, chart_dir.name, "-f-", "--debug")
# --atomic to avoid manual rollbacks
print("running: " + " ".join(cmd), file=sys.stderr)
subprocess.run(cmd, check=True, text=True, input=dumps(info, sort_keys=True, indent=4))
