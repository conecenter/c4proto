#!/usr/bin/env python3
from subprocess import PIPE, Popen
from http.server import BaseHTTPRequestHandler, HTTPServer
from os import environ
from logging import basicConfig, exception, DEBUG
from json import loads

def get_metrics_content():
    conf = loads(environ["C4DOCKER_HOSTS"])
    # Reuse SSH control master for long-lived session. No reconnections on each request.
    processes = [(c["host"], Popen((
        "timeout", "3", "ssh", "-o", "ControlMaster=auto", "-o", "ControlPath=/tmp/ssh_mux_%r_%h_%p",
        "-o", "ControlPersist=60", "-o", "ServerAliveInterval=4", "-o", "StrictHostKeyChecking=yes",
        "-o", f'UserKnownHostsFile={environ["C4DOCKER_KNOWN_HOSTS"]}', "-i", environ["C4DOCKER_KEY_PATH"], # use `ssh-keyscan $HOST` to get line for known_hosts
        f'{c["user"]}@{c["host"]}', "cat /proc/stat",
    ), stdout=PIPE)) for c in conf]
    titles = ("user","nice","system","idle","iowait","irq","softirq")
    return "\n".join(
        f'docker_cpu:{titles[i]}:seconds_total{{host="{host}"}} {v}'
        for host, proc in processes
        for line in proc.communicate()[0].decode().splitlines() if line.startswith("cpu ")
        for i, v in enumerate(line.split()[1:1+len(titles)])
    )
    # metrics are in jiffies - usually it is “% of one core”

def handle(path):
    try:
        if path == "/metrics": return 200, [("Content-Type", "text/plain")], get_metrics_content().encode()
        return 404, (), b''
    except Exception as e:
        exception(e)
        return 500, (), b''

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        status, headers, content = handle(self.path)
        self.send_response(status)
        for k, v in headers: self.send_header(k, v)
        self.end_headers()
        self.wfile.write(content)

basicConfig(level=DEBUG)
HTTPServer(("0.0.0.0", int(environ["C4DOCKER_METRICS_PORT"])), Handler).serve_forever()
