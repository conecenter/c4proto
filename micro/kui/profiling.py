from dataclasses import dataclass
from subprocess import check_output, Popen
from html import escape
from time import monotonic

from util import die, rget, post

@dataclass(frozen=True,slots=True)
class Profiling: proc: Popen; kc_exec: tuple[str,...]; at: float

def limit_by_list(v, l): return v if v in l else die(f"{v} not in {l}")

def get_exec(kc, pod_name):
    pod_names = check_output((*kc, "get", "pods", "-o", "name")).decode().splitlines()
    pod_nm = limit_by_list(f"pod/{pod_name}", pod_names)
    return (*kc, "exec", "-i", pod_nm, "--")

def get_exec_and_pid(kc, pod_name):
    kc_exec = get_exec(kc, pod_name)
    jcmd_lines = check_output((*kc_exec, "jcmd")).decode().splitlines()
    server_pids = [int(line.split()[0]) for line in jcmd_lines if "ServerMain" in line]
    pid = max(server_pids) if server_pids else die("ServerMain JVM not found")
    return kc_exec, pid

def init_profiling_profiling(kcs, make_html_response):
    mut_pr: dict[str,Profiling] = {}
    def respond(s, t): return { "profiling_status": s, "profiling_spent": t }
    @rget("/profiling.profiling")
    def load(mail):
        pi = mut_pr.get(mail)
        if not pi: return respond("", None)
        rc = pi.proc.poll()
        return respond(*(("P",monotonic()-pi.at) if rc is None else ("S",None) if rc == 0 else ("F",None)))
    @post("/profiling.profile")
    def make(mail, kube_context, pod_name, period):
        kc_exec, pid = get_exec_and_pid(kcs[kube_context], pod_name)
        check_output((*kc_exec, "tar", "-xzf-"), input=check_output(("tar","-czf-","-C","/tools","async")))
        # asprof is long; run detached, leaving the flamegraph in the pod. Status comes from proc.returncode;
        # the result is fetched on download (see handler) so there is no local file/pipe to manage.
        cmd = (*kc_exec, "async/bin/asprof", "-e", "itimer", "-f", "kui-profiled.html", "-d", str(int(period)), str(pid))
        mut_pr[mail] = Profiling(Popen(cmd), kc_exec, monotonic())
    @post("/profiling.reset_profile_status")
    def reset(mail): mut_pr.pop(mail).proc.terminate()
    @rget("/profiling.flamegraph.html")
    def download(mail): return make_html_response(check_output((*mut_pr[mail].kc_exec, "cat", "kui-profiled.html")))
    return load, make, reset, download

def init_profiling_thread(kcs, make_html_response):
    mut_thread_dumps = {}
    @rget("/profiling.thread_dump")
    def load(mail): return { "thread_dump_status": "S" if mail in mut_thread_dumps else "", }
    @post("/profiling.thread_dump")
    def make(mail, kube_context, pod_name):
        kc_exec, pid = get_exec_and_pid(kcs[kube_context], pod_name)
        dump_output = check_output((*kc_exec, "jcmd", str(pid), "Thread.print"))
        mut_thread_dumps[mail] = (
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    "<title>Thread Dump</title></head><body><pre>"
                    f"{escape(dump_output.decode())}"
                    "</pre></body></html>"
        ).encode()
    @post("/profiling.reset_thread_status")
    def reset(mail): mut_thread_dumps.pop(mail, None)
    @rget("/profiling.thread_dump.html")
    def download(mail): return make_html_response(mut_thread_dumps.pop(mail))
    return load, make, reset, download

def init_profiling_logback(kcs):
    mut_logback = {}
    @rget("/profiling.logback")
    def load(kube_context, pod_name): return { "logback_loaded": mut_logback.get((kube_context, pod_name)) }
    @post("/profiling.load_logback")
    def load_data(kube_context, pod_name):
        kc_exec = get_exec(kcs[kube_context], pod_name)
        logback_xml = (check_output((*kc_exec, "sh", "-c", "cat /tmp/logback.xml 2>/dev/null || true"))).decode()
        mut_logback[(kube_context, pod_name)] = logback_xml
    @post("/profiling.save_logback")
    def save(kube_context, pod_name, logback_xml):
        kc_exec = get_exec(kcs[kube_context], pod_name)
        check_output((*kc_exec, "sh", "-c", "cat > /tmp/logback.xml"), input=logback_xml.encode())
        mut_logback[(kube_context, pod_name)] = logback_xml
    @post("/profiling.unload_logback")
    def unload(kube_context, pod_name): mut_logback.pop((kube_context, pod_name), None)
    return load, load_data, save, unload

def init_profiling_gc(kcs):
    @post("/profiling.enable_gc_log")
    def enable_log(kube_context, pod_name):
        kc_exec, pid = get_exec_and_pid(kcs[kube_context], pod_name)
        check_output((*kc_exec, "jcmd", str(pid), "VM.log", "what=gc*"))
    return enable_log,
