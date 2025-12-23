from os import environ
from pathlib import Path
from subprocess import check_output, PIPE, STDOUT, Popen

from c4util import da, get_more_compile_options, parse_args

def with_prefix(pre, proc): return proc, Popen(da("perl","-pe",f"$|=1;s/^/={pre}= /"), stdin=proc.stdout)

def precompile(proj_tag, context):
    proto_dir = str(Path(__file__).parent)
    pr_env = {"PATH": environ["PATH"]}
    cl_cmd = da("perl", f"{proto_dir}/build_client.pl", context)
    cl_proc, cl_proc_log = with_prefix("client", Popen(cl_cmd, stdout=PIPE, stderr=STDOUT, env=pr_env))
    check_output(da("python3", f"{proto_dir}/build.py", context))
    tag_info, mod_dir, sbt_args, ok_path = get_more_compile_options(context, proj_tag)  # after build.py
    serv_proc, serv_proc_log = with_prefix("server", Popen(da(*sbt_args), stdout=PIPE, stderr=STDOUT))
    processes = [cl_proc, cl_proc_log, serv_proc, serv_proc_log]
    failed = [proc.args for proc in processes if proc.wait() != 0] # we let every process finish, even if some failed early
    if failed: raise Exception(failed)
    ok_path.write_bytes(b'')

precompile(**parse_args(('--proj-tag', '--context')))

# argparse.Namespace
# "docker/config.json"
