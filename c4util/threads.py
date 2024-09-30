
from queue import Queue
from typing import NamedTuple, Callable
from threading import Thread
from os import kill, getpid
from signal import SIGINT
from subprocess import Popen, PIPE, STDOUT
from time import monotonic, sleep
from socket import socket

class TaskFin(NamedTuple):
    ok: bool
    key: str
    value: any

class TaskQ:
    q: Queue
    active: dict
    all_ok: bool
    min_exec_time: int
    def __init__(self, q, min_exec_time, log):
        self.q = q
        self.active = {}
        self.all_ok = True
        self.min_exec_time = min_exec_time
        self.log = log
    def get(self):
        self.log(f'{len(self.active)} tasks in progress')
        msg = self.q.get()
        if isinstance(msg, TaskFin):
            del self.active[msg.key]
            self.all_ok = self.all_ok and msg.ok
        return msg
    def can_not_submit(self, task_key): return task_key in self.active
    def submit(self, proc, kv): # f must not fail
        task_key, value = kv
        if task_key in self.active: raise Exception(f"{task_key} exists")
        self.active[task_key] = value
        daemon(follow_process_q, proc, self.q, task_key, value, self.min_exec_time)
    def wait_all(self, need_ok):
        while True:
            if need_ok and not self.all_ok: raise Exception("failed")
            if len(self.active) == 0: break
            self.get()

def fatal(f, *args):
    res = []
    try: res.append(f(*args))
    finally: len(res) > 0 or kill(getpid(), SIGINT)

def daemon(*args): Thread(target=fatal, args=args, daemon=True).start()

def open_piped(cmd, cwd = None, env=None): return Popen(cmd, stdout=PIPE, stderr=STDOUT, cwd=cwd, env=env)

def log_addr(): return "127.0.0.1", 8001

def follow_process_q(proc, q, task_key, value, min_exec_time):
    until = monotonic() + min_exec_time
    ok = follow_process(proc, f'{value} [{task_key}]')
    sleep(max(0., until - monotonic()))
    q.put(TaskFin(ok, task_key, value))

def follow_process(proc, title):
    enc = lambda v: v.encode("utf-8")
    with socket() as sock:
        sock.connect(log_addr())
        sock.sendall(enc(f'{title} started -- {" ".join(proc.args)}\n'))
        hint = enc(f'{title.split()[0]} ')
        for line in proc.stdout: sock.sendall(hint + line)
        ok = proc.wait() == 0
        sock.sendall(enc(f'{title} {"succeeded" if ok else "failed"}\n'))
        return ok