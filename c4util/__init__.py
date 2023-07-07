
import json
import pathlib
import hashlib

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def one(it): return it

def parse_table(data):
    return [line.split() for line in data.split("\n") if len(line) > 0]

def read_json(path):
    with open(path,'r') as f:
        return json.load(f)

def path_exists(path):
    return pathlib.Path(path).exists()
def changing_text_observe(path, will): # we need to return save -- if code before save fails, state will remain unchanged
    return () if path_exists(path) and read_text(path) == will else (lambda:(
        pathlib.Path(path).write_text(will, encoding='utf-8', errors='strict')
    ),)
def changing_text(path, will):
    for save in changing_text_observe(path, will): save()
def read_text(path_str): return pathlib.Path(path_str).read_text(encoding='utf-8', errors='strict')


# suggest: read_json, subprocess.run
#
# parse_table