
import json
import subprocess
import sys
import os
from c4util import group_map, parse_table, read_json

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

def main(build_json_path,cp_path):
    allow_pkg_dep = {
        k: {k,*l} for k,l in read_json(build_json_path)["allow_pkg_dep"].items()
    }
    cp = read_json(cp_path)["CLASSPATH"]
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

script, *args = sys.argv
main(*args)
