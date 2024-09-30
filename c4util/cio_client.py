
from http.client import HTTPConnection
from uuid import uuid4
from json import dumps, loads
from sys import argv

from . import http_check, http_exchange

def localhost(): return "127.0.0.1"

def cmd_port(): return 8000

def rand_hint(): return str(uuid4()).split("-")[0]

def post_json(port, path, d):
    http_check(*http_exchange(HTTPConnection(localhost(), port), "POST", path, dumps(d).encode("utf-8")))

def main():
    steps_str, = argv[1:]
    steps = loads(steps_str)
    hint = rand_hint()
    post_json(cmd_port(), "/c4q", [["queue","hint",hint],*steps])
    return hint

# "PYTHONPATH": environ["C4CI_PROTO_DIR"]