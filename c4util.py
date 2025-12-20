from json import loads
from pathlib import Path
import sys
import re

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def changing_observe(path: Path, will: bytes): # we need to return save -- if code before save fails, state will remain unchanged
    return () if path.exists() and path.read_bytes() == will else (lambda: path.write_bytes(will),)

def da(*args):
    print(f"starting: {' '.join(str(a) for a in args)}", file=sys.stderr)
    return args

def die(a): raise a

def perl_exec(*lines): return "\n".join(('#!/usr/bin/perl', 'use strict;', *lines, 'die;'))

### compile utils

def rm_dots(path):
    res = re.sub("""/[^/]+/\.\./""", "/", path, count=1)
    return res if path == res else rm_dots(res)
def parse_classpath(s): return [rm_dots(path) for path in s.split(":") if len(path) > 0]

def get_more_compile_options(context, proj_tag):
    build_conf = loads(Path(f"{context}/target/c4/build.json").read_bytes())
    tag_info = build_conf["tag_info"][proj_tag]
    java_options = " ".join((line[2] for line in build_conf["plain"] if line[0] == "C4BUILD_JAVA_TOOL_OPTIONS"))
    mod_dir = f'{context}/target/c4/mod.{tag_info["mod"]}.d'
    sbt_args = ["env", "-C", mod_dir, f"JAVA_TOOL_OPTIONS={java_options}", "sbt", "-Dsbt.color=true", "c4build"]
    return tag_info, mod_dir, sbt_args
