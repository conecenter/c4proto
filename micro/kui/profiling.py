
from subprocess import check_call, run, check_output
from collections import namedtuple
from traceback import print_exc

Profiling = namedtuple("Profiling", ("status", "data"))

def get_name(obj): return obj["metadata"]["name"]

def never(m): raise Exception(m)

def limit_by_list(v, l): return v if v in l else never(f"{v} not in {l}")

def init_profiling(mut_pr, contexts, rt, kcp):
    profiling_contexts = [c["name"] for c in contexts]
    def load(mail, profiling_kube_context='', profiling_pod_name='', **_):
        profiling_status = mut_pr.get(mail,Profiling("","")).status
        return {"profiling_contexts": profiling_contexts, "profiling_status": profiling_status}
    def handle_profile(mail, kube_context, pod_name, period, **_):
        def run_profile():
            try:
                mut_pr[mail] = Profiling("P", "")
                kc = (*kcp, limit_by_list(kube_context, profiling_contexts))
                pod_names = check_output((*kc, "get", "pods", "-o", "name")).decode().splitlines()
                pod_nm = limit_by_list(f"pod/{pod_name}", pod_names)
                kc_exec = (*kc,"exec","-i",pod_nm,"--")
                jcmd_lines = check_output((*kc_exec, "jcmd")).decode().splitlines()
                pid = max(int(line.split()[0]) for line in jcmd_lines if "ServerMain" in line)
                tar = check_output(("tar","-czf-","-C","/tools","async"))
                run((*kc_exec, "tar", "-xzf-"), check=True, input=tar)
                check_call((*kc_exec, "async/bin/asprof", "-e", "itimer", "-f", "kui-profiled.html", "-d", str(int(period)), str(pid)))
                result = check_output((*kc_exec, "cat", "kui-profiled.html"))
                mut_pr[mail] = Profiling("S", result)
            except Exception as e:
                print_exc()
                mut_pr[mail] = Profiling("F", str(e))
        return run_profile
    def handle_reset_status(mail, **_): del mut_pr[mail]
    actions = {
        "profiling.load": load, "profiling.profile": handle_profile, "profiling.reset_status": handle_reset_status,
    }
    handlers = {
        "/profiling-flamegraph.html": rt.http_auth(lambda mail,**_: mut_pr.pop(mail).data.decode()),
    }
    return actions, handlers
