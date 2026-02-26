
from subprocess import check_call, run, check_output
from collections import namedtuple
from traceback import print_exc
from html import escape

Profiling = namedtuple("Profiling", ("status", "data"))

LogbackState = namedtuple("LogbackState", ("kube_context", "pod_name", "status", "data"))

def never(m): raise Exception(m)

def limit_by_list(v, l): return v if v in l else never(f"{v} not in {l}")

def init_profiling(mut_pr, contexts, rt, kcp):
    profiling_contexts = [c["name"] for c in contexts]
    mut_thread_dumps = {}
    mut_logback = {}

    def get_exec(kube_context, pod_name):
        kc = (*kcp, limit_by_list(kube_context, profiling_contexts))
        pod_names = check_output((*kc, "get", "pods", "-o", "name")).decode().splitlines()
        pod_nm = limit_by_list(f"pod/{pod_name}", pod_names)
        return (*kc, "exec", "-i", pod_nm, "--")

    def get_exec_and_pid(kube_context, pod_name):
        kc_exec = get_exec(kube_context, pod_name)
        jcmd_lines = check_output((*kc_exec, "jcmd")).decode().splitlines()
        server_pids = [int(line.split()[0]) for line in jcmd_lines if "ServerMain" in line]
        pid = max(server_pids) if server_pids else never("ServerMain JVM not found")
        return kc_exec, pid

    def load(mail, profiling_kube_context='', profiling_pod_name='', **_):
        profiling_status = mut_pr.get(mail, Profiling("", "")).status
        thread_dump_status = mut_thread_dumps.get(mail, Profiling("", "")).status
        logback = mut_logback.get(mail)
        logback_status, logback_loaded = (
            (logback.status, logback.data)
            if logback and logback.kube_context == profiling_kube_context and logback.pod_name == profiling_pod_name
            else ("",None)
        )
        return {
            "profiling_contexts": profiling_contexts,
            "profiling_status": profiling_status,
            "thread_dump_status": thread_dump_status,
            "logback_status": logback_status,
            "logback_loaded": logback_loaded,
        }
    def handle_profile(mail, kube_context, pod_name, period, **_):
        def run_profile():
            try:
                mut_pr[mail] = Profiling("P", "")
                kc_exec, pid = get_exec_and_pid(kube_context, pod_name)
                tar = check_output(("tar","-czf-","-C","/tools","async"))
                run((*kc_exec, "tar", "-xzf-"), check=True, input=tar)
                check_call((*kc_exec, "async/bin/asprof", "-e", "itimer", "-f", "kui-profiled.html", "-d", str(int(period)), str(pid)))
                result = check_output((*kc_exec, "cat", "kui-profiled.html"))
                mut_pr[mail] = Profiling("S", result)
            except Exception as e:
                print_exc()
                mut_pr[mail] = Profiling("F", str(e))
        return run_profile
    def handle_thread_dump(mail, kube_context, pod_name, **_):
        def run_thread_dump():
            try:
                mut_thread_dumps[mail] = Profiling("P", "")
                kc_exec, pid = get_exec_and_pid(kube_context, pod_name)
                dump_output = check_output((*kc_exec, "jcmd", str(pid), "Thread.print"))
                html = (
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    "<title>Thread Dump</title></head><body><pre>"
                    f"{escape(dump_output.decode())}"
                    "</pre></body></html>"
                )
                mut_thread_dumps[mail] = Profiling("S", html.encode())
            except Exception as e:
                print_exc()
                mut_thread_dumps[mail] = Profiling("F", str(e))
        return run_thread_dump
    def start_logback_processing(mail, kube_context, pod_name):
        logback = mut_logback.get(mail)
        mut_logback[mail] = LogbackState(kube_context, pod_name, "P", logback and logback.data)
    def handle_load_logback(mail, kube_context, pod_name, **_):
        def run_load_logback():
            try:
                start_logback_processing(mail, kube_context, pod_name)
                kc_exec = get_exec(kube_context, pod_name)
                logback_xml = check_output((*kc_exec, "sh", "-c", "cat /tmp/logback.xml 2>/dev/null || true")).decode()
                mut_logback[mail] = LogbackState(kube_context, pod_name, "S", logback_xml)
            except Exception as e:
                print_exc()
                mut_logback[mail] = LogbackState(kube_context, pod_name, "F", None)
        return run_load_logback
    def handle_save_logback(mail, kube_context, pod_name, logback_xml="", **_):
        def run_save_logback():
            try:
                start_logback_processing(mail, kube_context, pod_name)
                kc_exec = get_exec(kube_context, pod_name)
                check_output((*kc_exec, "sh", "-c", "cat > /tmp/logback.xml"), input=logback_xml.encode())
                mut_logback[mail] = LogbackState(kube_context, pod_name, "S", logback_xml)
            except Exception as e:
                print_exc()
                mut_logback[mail] = LogbackState(kube_context, pod_name, "F", None)
        return run_save_logback
    def handle_unload_logback(mail, **_):
        mut_logback.pop(mail, None)
    def handle_reset_profile_status(mail, **_):
        mut_pr.pop(mail, None)
    def handle_reset_thread_status(mail, **_):
        mut_thread_dumps.pop(mail, None)
    actions = {
        "profiling.load": load,
        "profiling.profile": handle_profile,
        "profiling.thread_dump": handle_thread_dump,
        "profiling.load_logback": handle_load_logback,
        "profiling.save_logback": handle_save_logback,
        "profiling.unload_logback": handle_unload_logback,
        "profiling.reset_profile_status": handle_reset_profile_status,
        "profiling.reset_thread_status": handle_reset_thread_status,
    }
    handlers = {
        "/profiling-flamegraph.html": rt.http_auth(lambda mail,**_: mut_pr.pop(mail).data.decode()),
        "/profiling-thread-dump.html": rt.http_auth(lambda mail,**_: mut_thread_dumps.pop(mail).data.decode()),
    }
    return actions, handlers
