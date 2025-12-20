
from argparse import ArgumentParser
from json import loads, dumps
from os import environ
import re
from hashlib import md5
from pathlib import Path
from subprocess import check_output, PIPE, STDOUT, Popen
from tempfile import TemporaryDirectory

from c4util import da, perl_exec, get_more_compile_options, die, parse_classpath

def need_dir(d: Path):
    d.mkdir(parents=True, exist_ok=True)
    return d

def with_prefix(pre, proc): return proc, Popen(da("perl","-pe",f"$|=1;s/^/={pre}= /"), stdin=proc.stdout)

def wait_all_ok(processes):
    failed = [proc.args for proc in processes if proc.wait() != 0] # we let every process finish, even if some failed early
    if failed: raise Exception(failed)

def build_type_rt(proj_tag, context, out):
    proto_dir = str(Path(__file__).parent)
    pr_env = {"C4CI_PROTO_DIR": proto_dir, "PATH": environ["PATH"]}
    cl_cmd = da("perl", f"{proto_dir}/build_client.pl", context)
    cl_proc, cl_proc_log = with_prefix("client", Popen(cl_cmd, stdout=PIPE, stderr=STDOUT, env=pr_env))
    check_output(da("python3", f"{proto_dir}/build.py", context))
    tag_info, mod_dir, sbt_args = get_more_compile_options(context, proj_tag)  # after build.py
    serv_proc, serv_proc_log = with_prefix("server", Popen(da(*sbt_args), stdout=PIPE, stderr=STDOUT))
    wait_all_ok([cl_proc, cl_proc_log, serv_proc, serv_proc_log])
    #
    paths = loads(check_output(da("python3", f"{proto_dir}/build_env.py", context, tag_info["mod"])).decode())
    chk_cmd = da("python3", "-u", f"{proto_dir}/chk_pkg_dep.py", "by_classpath", context, paths["CLASSPATH"])
    check_output(chk_cmd, env=pr_env)
    #
    tmp_life = TemporaryDirectory()
    res = f'{tmp_life.name}/res'
    app_dir = str(need_dir(Path(f"{res}/app")))
    #
    check_output(da("cp", f"{proto_dir}/run.pl", f"{proto_dir}/vault.py", f"{proto_dir}/ceph.pl", f"{res}/"))
    Path(f"{res}/serve.sh").write_bytes("\n".join((
        f'export C4MODULES={paths["C4MODULES"]}',
        "export C4APP_CLASS=ee.cone.c4actor.ParentElectorClientApp",
        f'export C4APP_CLASS_INNER={tag_info["main"]}',
        "exec java ee.cone.c4actor.ServerMain"
    )).encode())
    #
    re_cl = re.compile(r'\bclasses\b')
    wait_all_ok([(
        Popen(da("cp", p, f'{app_dir}/{p.split("/")[-1]}')) if p.endswith(".jar") else
        Popen(da("zip", "-q", "-r", f'{app_dir}/{md5(p.encode()).hexdigest()}.jar', "."), cwd=p) if re_cl.search(p) else
        die(Exception(f"bad path {p}"))
    ) for p in parse_classpath(paths["CLASSPATH"])])
    #
    re_split = re.compile(r'[^\s:]+')
    has_mod = {*re_split.findall(paths["C4MODULES"])}
    re_line = re.compile(r'(\S+)\s+\S+\s+(\S+)')
    public_part = [
        (p_dir, "".join(f"{sync}\n" for link, sync in pub), "".join(f"{link}\n" for link, sync in pub))
        for p_dir in re_split.findall(paths["C4PUBLIC_PATH"]) if Path(p_dir).exists()
        for pub in [[
            (link, sync)
            for link in Path(f"{p_dir}/c4gen.ht.links").read_bytes().decode().splitlines() if link
            for l_mod, sync in [re_line.fullmatch(link).group(1, 2)] if l_mod in has_mod
        ]] if pub
    ]
    for p_dir, sync, links in public_part:
        check_output(da("rsync", "-av", "--files-from", "-", f"{p_dir}/", f"{res}/htdocs"), text=True, input=sync)
    if public_part:
        Path(f"{res}/htdocs/c4gen.ht.links").write_bytes("".join(links for p_dir, sync, links in public_part).encode())
    #
    Path(res).rename(Path(out))

def main():
    parser = ArgumentParser()
    for a in ('--proj-tag', '--context', '--out'): parser.add_argument(a, required=True)
    build_type_rt(**vars(parser.parse_args()))

main()

# argparse.Namespace
# "docker/config.json"
