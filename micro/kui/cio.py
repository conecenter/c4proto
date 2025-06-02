
from functools import partial
from json import loads
from os import environ
from pathlib import Path
from subprocess import Popen, PIPE
import subprocess

from util import run_text_out, never, log, run, dumps

def sel(v, *path): return v if not path or v is None else sel(v.get(path[0]), *path[1:])

def get_kc(kube_context): return "kubectl","--kubeconfig",environ["C4KUBECONFIG"],"--context",kube_context

def get_ci_serve(kube_context):
    kc = get_kc(kube_context)
    cio_name, = run_text_out((*kc, "get", "deploy", "-l", "c4cio", "-o", "name")).split()
    return *kc, "exec", "-i", cio_name, "--", "python3", "-u", "/ci_serve.py"

def init_cio_tasks(mut_cio_tasks, active_contexts):
    def load(cio_kube_context='', **_):
        if not cio_kube_context: return { "need_filters": True }
        return { "items": [
            { "status": status, "task_name": task_name, "queue_name": queue_name }
            for report in [loads(mut_cio_tasks.get(cio_kube_context,"{}"))]
            for status, tasks in [
                ("active", sorted(report.get("active", {}).items())), ("pending", report.get("pending") or [])
            ]
            for queue_name, task_name in tasks
        ]}
    def watcher(kube_context):
        with Popen((*get_ci_serve(kube_context), "reporting"), text=True, stdout=PIPE) as proc:
            for line in proc.stdout: mut_cio_tasks[kube_context] = line
    watchers = [partial(watcher, c["name"]) for c in active_contexts]
    return watchers, { "cio_tasks.load": load }

def init_cio_logs(tmp_dir: Path, active_contexts, get_user_abbr, rt):
    def get_size(path): return path.stat().st_size if path.exists() else None
    def load_json_opt(path): return loads(path.read_bytes()) if path.exists() else None
    def load_int_opt(path): return int(path.read_bytes().decode()) if path.exists() else None
    def load(mail,**_):
        return {
            "all_log_sizes": [{
                "kube_context": c["name"], "log_size": get_size(get_all_log_path(c["name"]))
            } for c in active_contexts],
            "searching_size": get_size(get_searching_path(mail)),
            "search_result_size": get_size(get_search_res_path(mail)),
            "search_result_code": load_int_opt(get_search_res_code_path(mail)),
            "result_page": load_json_opt(get_page_path(mail)),
        }
    def get_all_log_path(kube_context): return tmp_dir / f"cio_log.all.{kube_context}"
    def get_searching_path(mail): return tmp_dir / f"cio_log.searching.{get_user_abbr(mail)}"
    def get_search_res_path(mail): return tmp_dir / f"cio_log.search_result.{get_user_abbr(mail)}"
    def get_search_res_code_path(mail): return tmp_dir / f"cio_log.search_result_code.{get_user_abbr(mail)}"
    def get_page_path(mail): return tmp_dir / f"cio_log.page.{get_user_abbr(mail)}"
    def search(mail, kube_context, query, **_):
        def run_search():
            get_searching_path(mail).unlink(missing_ok=True)
            get_search_res_path(mail).unlink(missing_ok=True)
            get_search_res_code_path(mail).unlink(missing_ok=True)
            get_page_path(mail).unlink(missing_ok=True)
            with get_searching_path(mail).open("wb") as f:
                all_log_path = get_all_log_path(kube_context)
                proc = subprocess.run(("grep", "-P", "-f-", str(all_log_path)), input=query.encode(), stdout=f)
            get_searching_path(mail).replace(get_search_res_path(mail)) # would protect from concurrent search
            get_search_res_code_path(mail).write_bytes(str(proc.returncode).encode())
            if proc.returncode == 0: write_page(mail, 1)
        return run_search
    def lines_per_page(): return 20
    def write_page(mail, page):
        search_res_path = get_search_res_path(mail)
        page_path = get_page_path(mail)
        if page <= 0: return
        take_line_count = page * lines_per_page()
        skip_line_count = (page-1) * lines_per_page()
        taken_lines = run_text_out(("tail","-n",str(take_line_count),str(search_res_path))).splitlines()
        lines = taken_lines[:-skip_line_count] if skip_line_count else taken_lines
        if lines: page_path.write_bytes(dumps({"lines":lines,"page":page}).encode())
    def goto_page(mail, page, **_): write_page(mail, page)
    def watcher(kube_context):
        with Popen((*get_ci_serve(kube_context), "consume_log"), text=True, stdout=PIPE, stdin=PIPE) as proc:
            match proc.stdout.readline().split():
                case ["BEGINNING", b, "END", _]:
                    proc.stdin.write(f'{b}\n')
                    proc.stdin.flush()
                    offset = int(b)
                case s: never(f'bad header: {s}')
            log(f"CIO LOG WATCH : {kube_context} {offset}")
            path = get_all_log_path(kube_context)
            path.unlink(missing_ok=True)
            with path.open("w") as f:
                for line in proc.stdout:
                    f.write(line)
                    f.flush()
    watchers = [partial(watcher, c["name"]) for c in active_contexts]
    actions = { "cio_logs.load": load, "cio_logs.search": search, "cio_logs.goto_page": goto_page }
    handlers = {
        "/cio-log-search-download": rt.http_auth(lambda mail,**_: get_search_res_path(mail).read_bytes().decode() ),
    }
    return watchers, actions, handlers

# todo better: example query; hide args; one line filters?
