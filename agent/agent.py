
import json
import pathlib
import os
import asyncio
import subprocess
import argparse
import sys

def get_conf(opt,name,token): return {
    "kind": "Config",
    "apiVersion": "v1",
    "clusters": [{
        "cluster": { "insecure-skip-tls-verify": True, "server": opt.server },
        "name": name
    }],
    "users": [{
        "user": { "token": token },
        "name": name
    }],
    "contexts": [{
        "context": { "cluster": name, "namespace": opt.ns, "user": name },
        "name": name
    }],
    "current-context": name,
    "preferences": {}
}

def write_text(path, data): path.write_text(data, encoding='utf-8', errors='strict')

def read_text(path): return path.read_text(encoding='utf-8', errors='strict')

#def exec(cmd, *args): os.execl(cmd,cmd,*args)

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--ns", required=True)
    parser.add_argument("--conf-path", required=True, type=pathlib.Path)
    return parser.parse_args()

def run_check_ver(loop):
    path = pathlib.Path(sys.argv[0])
    data = read_text(path)
    def iter():
        if data == read_text(path):
            loop.call_later(1,iter)
        else:
            loop.stop()
    iter()

def kc_conf_path(): return pathlib.Path("/tmp/c4kube-config-dev")

def run_make_config(loop,opt):
    def iter():
        if opt.conf_path.exists():
            conf = json.loads(read_text(opt.conf_path))
            kc_conf = get_conf(opt,"def",conf["key"])
            write_text(kc_conf_path(), json.dumps(kc_conf))
        else:
            print(f"waiting for {opt.conf_path}")
            loop.call_later(1,iter)
    iter()

def keep_running(loop,get_cmd):
    def iter(*was_list):
        if not was_list:
            cmd = get_cmd()
            if cmd: loop.call_later(5,iter,subprocess.Popen(cmd))
            else: loop.call_later(5,iter)
        else:
            proc, = was_list
            if proc.poll() != None:
                loop.call_soon(iter)
            else:
                if proc.args != get_cmd(): proc.terminate()
                loop.call_later(1,iter,proc)
    iter()

def run_setup_rsh(loop,opt):
    proto_dir = os.environ["C4CI_PROTO_DIR"]
    conf = json.loads(read_text(opt.conf_path))
    keep_running(loop,lambda:["perl",f"{proto_dir}/sync.pl","setup_rsh",str(kc_conf_path()),conf["user"]])

def run_port_forward(loop):
    pod_path = pathlib.Path("/tmp/c4pod")
    keep_running(loop, lambda: None if not pod_path.exists() else ["kubectl","--kubeconfig",str(kc_conf_path()),"port-forward","--address","0.0.0.0",read_text(pod_path),"4005"])

def main(loop):
    opt = parse_args()
    run_check_ver(loop)
    run_make_config(loop,opt)
    run_setup_rsh(loop,opt)
    run_port_forward(loop)

def exception_handler(loop,context):
    loop.stop()
    loop.default_exception_handler(context)

def async_run(f):
    loop = asyncio.new_event_loop()
    loop.set_exception_handler(exception_handler)
    f(loop)
    loop.run_forever()

async_run(main)