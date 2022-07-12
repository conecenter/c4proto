
import os
import json
import sys

### util

def read_json(path):
    with open(path,'r') as f:
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

def build_path(fn):
    dir = os.environ["C4CI_BUILD_DIR"]
    return f"{dir}/{fn}"

def load_dep(path):
    return [
        i_item
        for o_item in read_json(build_path(path))
        for i_item in (load_dep(o_item[2]) if o_item[0] == "C4INC" else [o_item])
    ]

def wrap_non_empty(before,value,after):
    return before + value + after if value else value

def get_list(d, k): return d[k] if k in d else []

def gen_mod(conf,mods,modId):
    src_dirs_str = "".join(
        f"""      file("{pre}/{"/".join(mod_tail)}"),\n"""
        for mod in mods
        for mod_head, *mod_tail in [mod.split(".")]
        for pre in conf["C4SRC"][mod_head]
    )
    ext_dep_str = "".join(sorted(set(
        "      " + "%".join(f""" "{part}" """ for part in dep.split(":")) + ",\n"
        for mod in mods
        for dep in get_list(conf["C4EXT"],mod)
    )))
    lib_dep_str = "".join(sorted(set(
        f"""      file("{dep}"),\n"""
        for mod in mods
        for dep in get_list(conf["C4LIB"],mod)
    )))
    return (
            f"""lazy val {modId} = project\n""" +
            f"""  .settings(\n""" +
            f"""    Compile / unmanagedSourceDirectories := Seq(\n{src_dirs_str}    ),\n""" +
            wrap_non_empty("    libraryDependencies := Seq(\n",ext_dep_str,"    ),\n") +
            wrap_non_empty("    Compile / unmanagedJars ++= Seq(\n",lib_dep_str,"    ),\n") +
            f"""  )\n"""
    )

def main(script,tag):
    conf = {
        k: group_map(l, lambda it: (it[0],it[1]))
        for k, l in group_map(load_dep("c4dep.main.json"), lambda it: (it[0],it[1:])).items()
    }
    full_dep = lazy_dict(lambda mod,get: sorted(set(
        [mod]+[d for dep in get_list(conf["C4DEP"],mod) for d in get(dep)]
    )))
    mod_str = (
        gen_mod(conf,full_dep(*(to for tos in conf["C4GENERATOR_MAIN"].values() for to in tos)),"generator") +
        gen_mod(conf,full_dep(tag),"main")
    )
    resolvers_str = "".join(f"""  ("{repo}" at "{one(*conf["C4REPO"][repo])}") ::\n""" for repo in sorted(conf["C4REPO"].keys()))
    print(f"""scalaVersion in ThisBuild := "2.13.6"\n{mod_str}\nresolvers ++=\n{resolvers_str}  Nil\n""")



main(*sys.argv)

#ThisBuild / exportJars := true
#subProjectID/compile
#fork := true
