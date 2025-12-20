
from sys import argv
import re
from json import loads
from pathlib import Path
from subprocess import check_output

from c4util import group_map, da


def parse_table(data): return [line.split() for line in data.split("\n") if len(line) > 0]

def get_base(pkg, coll):
    return pkg if (pkg in coll) or not pkg else get_base(".".join(pkg.split(".")[:-1]), coll)

def chk_line(line,allow_pkg_dep):
    fr, arrow, *to = line
    if fr == "Warning:": return True
    if arrow != "->": return False
    if fr=="classes": return True
    if "JDK" in to: return True
    if len(to) != 2: return False
    to_pkg, to_a = to
    if to_a != "classes": return to_a.endswith(".jar") or to_a.startswith("java.")
    from_pkg_base = get_base(fr, allow_pkg_dep)
    if not from_pkg_base: return False
    to_pkg_base = get_base(to_pkg, allow_pkg_dep[from_pkg_base])
    if not to_pkg_base: return False
    #if "kaf" in to_pkg_base : print(line,from_pkg_base,to_pkg_base,allow_pkg_dep[from_pkg_base])
    return True


def load_conf(context): return loads(Path(f"{context}/target/c4/build.json").read_bytes())


def get_full_deps(build_conf): return {k: {k, *l} for k, l in build_conf["allow_pkg_dep"].items()}


def handle_by_classpath(context, cp):
    build_conf = load_conf(context)
    restrict(context, build_conf)
    allow_pkg_dep = get_full_deps(build_conf)
    cp_by_tp = group_map(cp.split(":"), lambda p: (
        "jar" if p.endswith(".jar") else
        "classes" if p.endswith("/classes") else
        "-"
    ,p))
    if "-" in cp_by_tp: raise cp_by_tp
    jdeps_res = check_output(da("jdeps", "--multi-release", "16", "-cp", cp, *cp_by_tp["classes"])).decode()
    bad = "".join(
        " ".join(line)+"\n" for line in parse_table(jdeps_res) if not chk_line(line,allow_pkg_dep)
    )
    if bad: raise Exception(f"bad dep:\n[{bad}]")


def handle_by_text(context):
    build_conf = load_conf(context)
    restrict(context, build_conf)
    allow_pkg_dep = get_full_deps(build_conf)
    print(next(line[0] for line in build_conf["plain"]))
    mod_prefix = next(line[2] for line in build_conf["plain"] if line[0] == "C4DEP_REASONING_PREFIX")
    group, point, pkg_pre = mod_prefix.partition(".")
    if point != ".":
        raise Exception(f"bad conf: {mod_prefix}")
    src_dirs = sorted(f"{context}/{line[2]}" for line in build_conf["plain"] if line[0] == "C4SRC" and line[1] == group)
    re_imp = re.compile("^import\\s+(\\S*)", re.M)
    found = [
        (path, imp_list, (
            None if not imp_list else
            f"\nmissing or unconnected module of {from_pkg}" if not pkg_base else
            f"\nbad deps in {path}:{''.join(bad_imp_lines)}" if bad_imp_lines else
            None
        ))
        for src_dir in src_dirs
        for path in sorted(check_output(da("find", src_dir, "-type", "f", "-name", "*.scala")).decode().splitlines())
        for path_parts in [path[len(src_dir)+1:].split("/")]
        for fn_parts in [path_parts[-1].split(".")]
        if not (fn_parts[0] == "c4gen" and len(fn_parts) == 3)
        # so if scala is generated from non-scala, it can introduce new deps
        for from_pkg in [".".join(path_parts[:-1])]
        for pkg_base in [get_base(from_pkg, allow_pkg_dep)]
        for imp_list in [[imp for imp in re_imp.findall(Path(path).read_bytes().decode()) if imp.startswith(pkg_pre)]]
        for bad_imp_lines in [pkg_base and [f"\n {to}" for to in imp_list if not get_base(to, allow_pkg_dep[pkg_base])]]
    ]
    # for path, imp_list, msg in found:
    #     if "..." in path:
    #         print(path, imp_list, msg)
    res = "".join(sorted({msg for path, imp_list, msg in found if msg}))
    if res:
        raise Exception(res)


def restrict(context, build_conf):
    path = Path(f"{context}/uses.re")
    if not path.exists(): return
    dep_str = "".join(sorted(f'{k}->{v}\n' for k, l in build_conf["allow_pkg_dep"].items() for v in l))
    found = "".join(f'\t{m}\n' for m in re.findall(path.read_bytes().decode(), dep_str, re.VERBOSE))
    if found: raise Exception(f'restricted deps found:\n{found}')


def main():
    handlers = {"by_classpath": handle_by_classpath, "by_text": handle_by_text}
    script, op, *args = argv
    handlers[op](*args)


main()
