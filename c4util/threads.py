
from queue import Queue
from typing import NamedTuple
from threading import Thread
from os import kill, getpid
from signal import SIGINT
from subprocess import Popen, PIPE, STDOUT
from time import monotonic


class TaskFin(NamedTuple):
    ok: bool
    key: str
    value: any
    log_path: str

class TaskQ:
    q: Queue
    active: set
    all_ok: bool
    def __init__(
            self,
            get_log_path,
            log_starting = lambda proc, log_path: (),
            log_finished = lambda msg: (),
            log_progress = lambda count: (),
    ):
        self.q = Queue()
        self.active = set()
        self.all_ok = True
        self.get_log_path = get_log_path
        self.log_starting = log_starting
        self.log_finished = log_finished
        self.log_progress = log_progress
    def get(self):
        self.log_progress(len(self.active))
        msg = self.q.get()
        if isinstance(msg, TaskFin):
            self.active.remove(msg.key)
            self.all_ok = self.all_ok and msg.ok
            self.log_finished(msg)
        return msg
    def can_not_submit(self, task_key): return task_key in self.active
    def submit(self, proc, task_key, value, log_path=None): # f must not fail
        log_path = log_path if log_path is not None else self.get_log_path()
        task_key = task_key if len(task_key) > 0 else log_path
        self.log_starting(proc, log_path)
        if task_key in self.active: raise Exception(f"{task_key} exists")
        self.active.add(task_key)
        daemon(lambda: self.q.put(TaskFin(measure(proc, log_path), task_key, value, log_path)))
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

def measure(proc, log_path):
    started = monotonic()
    with open(log_path, "w") as log_file:
        for line in proc.stdout:
            print(f"{str(int(monotonic()-started)).zfill(5)} {line}", end="", file=log_file, flush=True)
    return proc.wait() == 0