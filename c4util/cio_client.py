
from http.client import HTTPConnection
from uuid import uuid4
from json import dumps, loads
from sys import argv, stdout
from socket import create_connection

from . import http_check, http_exchange, one

def localhost(): return "127.0.0.1"

def cmd_addr(): return localhost(), 8000

def log_addr(): return localhost(), 8001

def reporting_addr(): return localhost(), 8002

def task_kv(arg):
    uid = str(uuid4())
    return uid, f'{uid.split("-")[0]}-{arg}'

def task_hint(arg): return one(*task_kv(arg)[1:])

def post_json(addr, path, d):
    http_check(*http_exchange(HTTPConnection(*addr), "POST", path, dumps(d).encode("utf-8")))

def main():
    steps_str, = argv[1:]
    if steps_str == "reporting": return reporting()
    steps = loads(steps_str)
    hint = task_hint("call")
    post_json(cmd_addr(), "/c4q", [["queue","hint",hint],*steps])
    return hint

def reporting():
    with create_connection(reporting_addr()) as sock:
        while True:
            data = sock.recv(4096)
            if not data: break  # connection closed
            stdout.buffer.write(data)
            stdout.buffer.flush()
