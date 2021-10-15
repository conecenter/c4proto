
import json
import os

def read_json(path):
    with open(path,'r') as f:
        return json.load(f)

def group(l):
    res = {}
    for k,v in l:
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def build_path(fn):
    dir = os.environ["C4CI_BUILD_DIR"]
    return f"{dir}/{fn}"

def load_dep(fn): return [
    it
    for cmd,fr,to in read_json(build_path(fn))
    for it in ([(cmd,(fr,to))] if cmd!="C4INC" else load_dep(to))
]






def main():
    config_statements = group(load_dep("c4dep.main.json"))
    c4dep_dict = group(config_statements["C4DEP"])
    c4ext_dict = group(config_statements["C4EXT"])
    c4src_dict = group(config_statements["C4SRC"])
    c4lib_dict = group(config_statements["C4LIB"])

    sorted(
        {fr for fr, to in config_statements["C4EXT"]} +
        {it for fr_to in config_statements["C4DEP"] for it in fr_to}
    )


#C4REPO
main()