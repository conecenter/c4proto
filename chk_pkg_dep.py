
import json
import subprocess
import sys
import os
import re
from c4util import group_map, parse_table, read_json, read_text
from c4util.build import run_text_out

### util

def print_args(args):
    if "C4PRINT_ARGS" in os.environ: print("running: "+" ".join(args))
    return args

def run(args):
    proc = subprocess.run(print_args(args),capture_output=True,text=True)
    if proc.returncode != 0:
        print(proc.stdout,proc.stderr)
    proc.check_returncode()
    return proc

### main

def get_base(pkg, coll):
    return pkg if (pkg in coll) or not pkg else get_base(".".join(pkg.split(".")[:-1]), coll)

def chk_line(line,allow_pkg_dep):
    fr, arrow, *to = line
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


def load_conf(context): return read_json(f"{context}/target/c4/build.json")


def get_full_deps(build_conf): return {k: {k, *l} for k, l in build_conf["allow_pkg_dep"].items()}


def handle_by_classpath(context, cp):
    build_conf = load_conf(context)
    allow_pkg_dep = get_full_deps(build_conf)
    cp_by_tp = group_map(cp.split(":"), lambda p: (
        "jar" if p.endswith(".jar") else
        "classes" if p.endswith("/classes") else
        "-"
    ,p))
    if "-" in cp_by_tp: raise cp_by_tp
    jdeps_res = run(["jdeps","--multi-release","16","-cp",cp,*cp_by_tp["classes"]]).stdout
    bad = "".join(
        " ".join(line)+"\n" for line in parse_table(jdeps_res) if not chk_line(line,allow_pkg_dep)
    )
    if bad: raise Exception(f"bad dep:\n{bad}")


def handle_by_text(context):
    build_conf = load_conf(context)
    allow_pkg_dep = get_full_deps(build_conf)
    print(next(line[0] for line in build_conf["plain"]))
    mod_prefix = next(line[2] for line in build_conf["plain"] if line[0] == "C4DEP_REASONING_PREFIX")
    group, point, pkg_prefix = mod_prefix.partition(".")
    if point != ".":
        raise Exception(f"bad conf: {mod_prefix}")
    src_dirs = sorted(line[2] for line in build_conf["plain"] if line[0] == "C4SRC" and line[1] == group)
    re_pkg = re.compile("^package\\s+(\\S*)", re.M)
    re_imp = re.compile("^import\\s+(\\S*)", re.M)
    res = [
        f"{path}\n  {imp}"
        for path in sorted(run_text_out(("find", *src_dirs, "-type", "f", "-name", "*.scala")).splitlines())
        if "/c4gen." not in path for content in [read_text(path)]
        for pkg in re_pkg.findall(content) for from_pkg_base in [get_base(pkg, allow_pkg_dep)] if from_pkg_base
        for imp in re_imp.findall(content)
        if imp.startswith(pkg_prefix) and not get_base(imp, allow_pkg_dep[from_pkg_base])
    ]
    if res:
        print("\n".join(res))
        raise Exception(f"bad deps")


def main():
    handlers = {"by_classpath": handle_by_classpath, "by_text": handle_by_text}
    script, op, *args = sys.argv
    handlers[op](*args)


main()
