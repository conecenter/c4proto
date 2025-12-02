
from pathlib import Path
from base64 import b64encode, b64decode
from gzip import compress
from re import finditer, MULTILINE
from subprocess import check_output, check_call
from json import dumps, loads
from tempfile import TemporaryDirectory
from sys import stdin
from traceback import print_exc
from os import kill, getpid
from signal import SIGTERM
from queue import Queue
from threading import Thread
from logging import info
from datetime import datetime, timezone

def b64gz(s: bytes) -> str: return b64encode(compress(s)).decode()

def need_profiler(out_dir, pid):
    prof_bin = "/tmp/c4dig/async/bin/asprof"
    if "Profiling is running" not in check_output((prof_bin, "status", str(pid))).decode():
        check_call((prof_bin, "-e", "itimer,alloc", "--loop", "1m", "-f", f"{out_dir}/{pid}.%t.jfr", str(pid)))

def extract_send_metrics(regex, data):
    for m in finditer(regex, data, MULTILINE):
        for k,v in m.groupdict().items():
            if v is not None: send({ "tp": "metrics", "key": k, "value": v })

def now_fmt(): return datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")

def report_jcmd(out_dir, pid):
    tmp_life = TemporaryDirectory()
    path = Path(tmp_life.name) / "commands"
    #thread_cmd = f'Thread.dump_to_file -format=json {out_dir}/{pid}.{now_fmt()}.threads.json'
    thread_cmd = "Thread.print"
    path.write_bytes(f"{thread_cmd}\nGC.heap_info\nVM.flags\n".encode())
    data = check_output(("jcmd", str(pid), "-f", str(path)))
    Path(f'{out_dir}/{pid}.{now_fmt()}.jcmd-out.txt').write_bytes(data)
    mre = r'^\s*garbage-first\s+heap\s+total\s+\d+K,\s*used\s+(?P<heap_used_kb>\d+)K\b|-XX:MaxHeapSize=(?P<heap_max>\d+)\b'
    extract_send_metrics(mre, data.decode())

def report_proc_status(out_dir, pid):
    data = Path(f'/proc/{str(pid)}/status').read_bytes()
    Path(f'{out_dir}/{pid}.{now_fmt()}.proc-status.txt').write_bytes(data)
    mre = r'^Threads:\s*(?P<threads>\d+)$|^VmHWM:\s*(?P<vm_hwm_kb>\d+)\s*kB$|^VmPeak:\s*(?P<vm_peak_kb>\d+)\s*kB$|^VmRSS:\s*(?P<vm_rss_kb>\d+)\s*kB$'
    extract_send_metrics(mre, data.decode())

def get_pid():
    pids = [int(line.split()[0]) for line in check_output(["jcmd"]).decode().splitlines() if "ServerMain" in line]
    return max(pids) if pids else None

def send(arg): print(dumps(arg), flush=True)

def handle(req):
    prof_out_dir = Path("/tmp/c4dig/async_out")
    match req:
        case ["install", data]: check_output(("tar", "-C", "/tmp", "-xzf-"), input=b64decode(data))
        case ["rm", nm]: (prof_out_dir / nm).unlink()
        case ["st"]:
            pid = get_pid()
            if pid is not None:
                need_profiler(prof_out_dir, pid)
                report_proc_status(prof_out_dir, pid)
                report_jcmd(prof_out_dir, pid)
            for p in prof_out_dir.iterdir(): send({"tp": "file", "name": p.name, "data": b64gz(p.read_bytes())})

def watchdog(alarm_q, timeout):
    try:
        while True: alarm_q.get(timeout=timeout)
    except:
        print_exc()
        kill(getpid(), SIGTERM)

def main():
    alarm_q = Queue()
    Thread(target=watchdog, args=(alarm_q, 60), daemon=True).start()
    for line in stdin:
        try:
            info(line)
            handle(loads(line))
            alarm_q.put(None)
        except: print_exc()
