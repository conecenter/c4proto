
import json
import subprocess
import sys
import pathlib
import re

### util

def read_json(path):
    with path.open() as f:
        return json.load(f)

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res


def build_path(): return pathlib.Path.cwd()

def tmp_path(): return build_path() / "target/c4"

### main

def get_base(pkg, coll):
    return pkg if (pkg in coll) or not pkg else get_base(".".join(pkg.split(".")[:-1]), coll)

def chk_line(line,allow_pkg_dep):
    if not line: return True
    match = re.fullmatch("classes\s+->\s+\S+|\s+(\S+)\s+->\s+(\S+)\s+(\S+)",line)
    if not match: return False
    from_pkg, to_pkg, to_a = match.groups()
    if not to_a or to_a.endswith(".jar") or to_a.startswith("java."): return True
    if to_a != "classes": return False
    from_pkg_base = get_base(from_pkg, allow_pkg_dep)
    #if from_pkg_base!=from_pkg: print(f"from_pkg {from_pkg} / {from_pkg_base}")
    if not from_pkg_base: return False
    to_pkg_base = get_base(to_pkg, allow_pkg_dep[from_pkg_base])
    if not to_pkg_base: return False
    #if to_pkg_base!=to_pkg: print(f"to_pkg {to_pkg} / {to_pkg_base}")
    return True

def main(script,mod):
    allow_pkg_dep = {
        k: {k,*l} for k,l in read_json(tmp_path() / "build.json")["allow_pkg_dep"].items()
    }
    cp = read_json(tmp_path() / f"mod.{mod}.classpath.json")["CLASSPATH"]
    cp_by_tp = group_map(cp.split(":"), lambda p: (
        "jar" if p.endswith(".jar") else
        "classes" if p.endswith("/classes") else
        "-"
    ,p))
    if "-" in cp_by_tp: raise cp_by_tp
    jdeps_cmd = ["jdeps","-cp",cp,*cp_by_tp["classes"]]
    jdeps_res = subprocess.run(jdeps_cmd,check=True,text=True,capture_output=True).stdout
    bad = "".join(
        f"{line}\n" for line in jdeps_res.split("\n") if not chk_line(line,allow_pkg_dep)
    )
    if bad: raise Exception(f"bad dep:\n[{bad}]")

main(*sys.argv)