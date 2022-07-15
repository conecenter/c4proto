
import os
import json
import sys
#import os.path
import pathlib

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

def one(it): return it

def lazy_dict(f):
    h = {}
    def get(k):
        if k not in h: h[k] = f(k,get)
        return h[k]
    return get

###
def write_changed(path,data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not (path.exists() and data == path.read_text(encoding='utf-8', errors='strict')):
        path.write_text(data, encoding='utf-8', errors='strict')

def build_path(): return pathlib.Path.cwd()

def load_dep(path):
    return [
        i_item
        for o_item in read_json(build_path() / path)
        for i_item in (load_dep(o_item[2]) if o_item[0] == "C4INC" else [o_item])
    ]

def wrap_non_empty(before,value,after):
    return before + value + after if value else value

def get_list(c, a, k):
    d = c[a] if a in c else {}
    return d[k] if k in d else []

def get_src_dirs(conf,mods):
    return [
        f"""{pre}/{"/".join(mod_tail)}"""
        for mod in mods
        for mod_head, *mod_tail in [mod.split(".")]
        for pre in conf["C4SRC"][mod_head]
    ]

def tmp_path(): return build_path() / ".bloop/c4"

def leave_tmp(dir): return f"""../../../{dir}"""

def to_sbt(src_dirs,ext_dep_list,lib_dep_list,repo_dict):
    src_dirs_str = "".join(
        f"""  baseDirectory.value / "{dir}",\n""" for dir in src_dirs
    )
    ext_dep_str = "".join(
        "  " + "%".join(f""" "{part}" """ for part in dep.split(":")) + ",\n"
        for dep in ext_dep_list
    )
    lib_dep_str = "".join(
        f"""  baseDirectory.value / "{path}",\n""" for path in lib_dep_list
    )
    resolvers_inner_str = "".join(
        f"""  ("{repo}" at "{one(*repo_dict[repo])}") ::\n"""
        for repo in sorted(repo_dict.keys())
    )
    return (
        f"""scalaVersion in ThisBuild := "2.13.8"\n\n""" +
        f"""Compile / unmanagedSourceDirectories := Seq(\n{src_dirs_str})\n\n""" +
        wrap_non_empty("libraryDependencies := Seq(\n",ext_dep_str,")\n\n") +
        wrap_non_empty("Compile / unmanagedJars ++= Seq(\n",lib_dep_str,")\n\n") +
        wrap_non_empty("resolvers ++=\n",resolvers_inner_str,"  Nil\n\n")
    )

def flat_values(d):
    return sorted({to for tos in d.values() for to in tos})

def main(script):
    conf_plain = load_dep("c4dep.main.json")
    conf = {
        k: group_map(l, lambda it: (it[0],it[1]))
        for k, l in group_map(conf_plain, lambda it: (it[0],it[1:])).items()
    }
    full_dep = lazy_dict(lambda mod,get: sorted({
        mod, *(d for dep in get_list(conf,"C4DEP",mod) for d in get(dep))
    }))
    mod_heads = sorted({
        *(".".join(main.split(".")[0:-1]) for main in flat_values(conf["C4TAG"])),
        *flat_values(conf["C4GENERATOR_MAIN"])
    })
    repo_dict = conf["C4REPO"] if "C4REPO" in conf else {}
    for mod in mod_heads:
        mods = full_dep(mod)
        src_dirs = [leave_tmp(dir) for dir in get_src_dirs(conf,mods)]
        ext_dep_list = sorted({
            dep for mod in mods for dep in get_list(conf,"C4EXT",mod)
        })
        lib_dep_list = sorted({
            leave_tmp(dep) for mod in mods for dep in get_list(conf,"C4LIB",mod)
        })
        mod_text = to_sbt(src_dirs,ext_dep_list,lib_dep_list,repo_dict)
        write_changed(tmp_path() / f"mod.{mod}.d" / "build.sbt", mod_text)
    ide_sbt_text = to_sbt(
        flat_values(conf["C4SRC"]), flat_values(conf["C4EXT"]), [], repo_dict
    )
    write_changed(build_path() / "c4gen-generator.sbt", ide_sbt_text)

    out_conf = {
        "plain": conf_plain,
        "src_dirs_by_tag": { mod_name: get_src_dirs(conf,full_dep(mod_name)) for mod_name in mod_heads },
        "src_dirs_generator_off": [
            dir
            for mod in get_list(conf,"C4GENERATOR_MODE","OFF")
            for dir in get_src_dirs(conf,full_dep(mod))
        ],
    }
    write_changed(tmp_path() / "build.json", json.dumps(out_conf, sort_keys=True, indent=4))

main(*sys.argv)

#ThisBuild / exportJars := true
#subProjectID/compile
#fork := true
