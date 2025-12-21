import re
from hashlib import md5
from json import loads
from pathlib import Path
from subprocess import check_output, Popen
from tempfile import TemporaryDirectory

from c4util import get_more_compile_options, da, die, parse_classpath, parse_args

def finish(proj_tag, context, out):
    proto_dir = str(Path(__file__).parent)
    tag_info, mod_dir, sbt_args, ok_path = get_more_compile_options(context, proj_tag)
    if not ok_path.exists(): raise Exception("not precompiled")
    #
    paths = loads(check_output(da("python3", f"{proto_dir}/build_env.py", context, tag_info["mod"])).decode())
    check_output(da("python3", "-u", f"{proto_dir}/chk_pkg_dep.py", "by_classpath", context, paths["CLASSPATH"]))
    #
    tmp_life = TemporaryDirectory()
    res = f'{tmp_life.name}/res'
    app_dir = f"{res}/app"
    Path(app_dir).mkdir(parents=True, exist_ok=True)
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
    processes = [(
        Popen(da("cp", p, f'{app_dir}/{p.split("/")[-1]}')) if p.endswith(".jar") else
        Popen(da("zip", "-q", "-r", f'{app_dir}/{md5(p.encode()).hexdigest()}.jar', "."), cwd=p) if re_cl.search(p) else
        die(Exception(f"bad path {p}"))
    ) for p in parse_classpath(paths["CLASSPATH"])]
    for proc in processes: proc.wait() == 0 or die(Exception("failed to copy"))
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

finish(**parse_args(('--proj-tag', '--context', '--out')))
