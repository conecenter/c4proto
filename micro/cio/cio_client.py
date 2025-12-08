
from http.client import HTTPConnection
from threading import Thread
from uuid import uuid4
from json import dumps, loads
from sys import argv, stdout, stdin
from socket import create_connection

from util import http_check, http_exchange, one



def localhost(): return "127.0.0.1"

def cmd_addr(): return localhost(), 8000

def log_addr(): return localhost(), 8001

def reporting_addr(): return localhost(), 8002

def kafka_addr(offset): return localhost(), 9000 + offset

def task_kv(arg):
    uid = str(uuid4())
    return uid, f'{uid.split("-")[0]}-{arg}'

def task_hint(arg): return one(*task_kv(arg)[1:])

def post_json(addr, path, d):
    http_check(*http_exchange(HTTPConnection(*addr), "POST", path, dumps(d).encode("utf-8")))

def post_steps(steps): post_json(cmd_addr(), "/c4q", steps)

def log_topic(): return "cio_log.0"

def ev_topic(): return "cio_ev.0"

def consume(topic):
    with create_connection(kafka_addr(0)) as sock:
        def sender():
            sock.sendall(f"CONSUME {topic}\n".encode())
            sock.sendall(stdin.readline().encode())
        Thread(target=sender, daemon=True).start()
        return to_stdout(sock)

def main():
    match argv[1:]:
        case ["reporting"]:
            with create_connection(reporting_addr()) as sock:
                return to_stdout(sock)
        case ["consume_log"]: consume(log_topic())
        case ["consume_events"]: consume(ev_topic())
        case [steps_str]:
            steps = loads(steps_str)
            hint = task_hint("call")
            post_steps([["queue","hint",hint],*steps])
            print(hint)
        case _: raise Exception("bad args")

def to_stdout(sock):
    while True:
        data = sock.recv(4096)
        if not data: break  # connection closed
        stdout.buffer.write(data)
        stdout.buffer.flush()
