
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
    value: str

class TaskQ:
    q: Queue
    active: dict
    all_ok: bool
    min_exec_time: int
    def __init__(self, q, min_exec_time, log, log_addr):
        self.q = q
        self.active = {}
        self.all_ok = True
        self.min_exec_time = min_exec_time
        self.log = log
        self.log_addr = log_addr
    def get(self):
        self.log(f'{len(self.active)} tasks in progress')
        msg = self.q.get()
        if isinstance(msg, TaskFin):
            del self.active[msg.key]
            self.all_ok = self.all_ok and msg.ok
        return msg
    def can_not_submit(self, task_key): return task_key in self.active
    def submit(self, task_key, value):
        if task_key in self.active: raise Exception(f"{task_key} exists")
        self.active[task_key] = value
        return lambda cmd, cwd = None, env=None: daemon(self.follow, task_key, value, cmd, cwd, env)
    def follow(self, task_key, value, cmd, cwd, env):
        until = monotonic() + self.min_exec_time
        title = f'{value} [{task_key}]'
        with Popen(cmd, stdout=PIPE, stderr=STDOUT, cwd=cwd, env=env) as proc, socket() as sock:
            sock.connect(self.log_addr)
            sock.sendall(encode(f'{title} started -- {" ".join(proc.args)}\n'))
            hint = encode(f'{title.split()[0]} ')
            for line in proc.stdout: sock.sendall(hint + line)
            ok = proc.wait() == 0
            sock.sendall(encode(f'{title} {"succeeded" if ok else "failed"}\n'))
        sleep(max(0., until - monotonic()))
        self.q.put(TaskFin(ok, task_key, value))
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

def encode(v): return v.encode("utf-8")
