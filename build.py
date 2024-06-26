
import json
import sys
import pathlib
from c4util import group_map, one, read_text, changing_text_observe, read_json, run
from c4util.build import dir_sum


### util

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

def load_dep(build_path_str, path):
    return [
        i_item
        for o_item in read_json(f"{build_path_str}/{path}")
        for i_item in (load_dep(build_path_str, o_item[2]) if o_item[0] == "C4INC" else [o_item])
    ]

def wrap_non_empty(before,value,after):
    return before + value + after if value else value

def get_list(c, a, k):
    return c.get(a, {}).get(k, [])

def get_src_dirs(conf,mods):
    return [
        f"""{pre}/{"/".join(mod_tail)}"""
        for mod in mods
        for mod_head, *mod_tail in [mod.split(".")]
        for pre in conf["C4SRC"][mod_head]
    ]

def leave_tmp(dir): return f"""../../../../{dir}"""

def colon_to_q(joiner,v):
    return joiner.join(f""" "{part}" """ for part in v.split(":"))

def to_sbt_mod(name,project,dep_name,src_dirs,ext_dep_list,lib_dep_list,excl,get_repo,warts):
    src_dirs_str = "".join(
        f"""   baseDirectory.value / "{dir}",\n""" for dir in src_dirs
    )
    repo_str = "".join(
        f"""   ("{name}_{i}" at "{repo}"),\n"""
        for i, repo in enumerate(sorted({
            repo
            for dep in ext_dep_list
            for repo in get_repo(dep)
        }))
    )
    ext_dep_str = "".join(
        "   " + colon_to_q("%",dep) +
        "".join(" exclude("+colon_to_q(",",e)+")" for e in excl(dep)) +
        ",\n"
        for dep in ext_dep_list
    )
    lib_dep_str = "".join(
        f"""   baseDirectory.value / "{path}",\n""" for path in lib_dep_list
    )
    warts_str = "".join(f"""   {wart},\n""" for wart in warts)
    return (
        f"""lazy val {name} = {project}\n""" +
        wrap_non_empty(" .dependsOn(",dep_name," )\n") +
        wrap_non_empty(" .settings(\n",
            wrap_non_empty("  Compile / unmanagedSourceDirectories := Seq(\n",src_dirs_str,"  ),\n") +
            wrap_non_empty("  resolvers ++= Seq(\n",repo_str,"  ),\n") +
            wrap_non_empty("  libraryDependencies ++= Seq(\n",ext_dep_str,"  ),\n") +
            wrap_non_empty("  Compile / unmanagedJars ++= Seq(\n",lib_dep_str,"  ),\n") +
            wrap_non_empty("  wartremoverErrors ++= Seq(\n",warts_str,"  ),\n"),
        " )\n")
    )

def flat_values(d):
    return sorted({to for tos in d.values() for to in tos})

def parse_main(main):
    parts = main.split(".")
    return {
        "mod": ".".join(parts[:-1]),
        "main": ".".join(parts[1:]),
        "name": parts[0],
    }

def to_id(m): return "mod_" + m.replace(".","_")

def get_pkg_from_mod(mod): return ".".join(mod.split(".")[1:])


def main(build_path_str):
    tmp_part = "target/c4"
    conf_plain = load_dep(build_path_str, "c4dep.main.json")
    generate_configs(build_path_str, tmp_part, conf_plain)
    compile_run_generator(build_path_str, tmp_part, conf_plain)


def compile_run_generator(build_path_str, tmp_part, conf_plain):
    generator_conf, = [line[1:] for line in conf_plain if line[0] == "C4GENERATOR_MAIN"]
    g_main, g_mod = generator_conf
    proj_part = f"{tmp_part}/mod.{g_mod}.d"
    g_sum = dir_sum(build_path_str, read_json(f"{build_path_str}/{proj_part}/c4sync_paths.json"), ("-name", "*.scala"))
    for save in changing_text_observe(f"{build_path_str}/{tmp_part}/generator-src-sum", g_sum):
        run(("sbt", "c4build"), cwd=f"{build_path_str}/{proj_part}")
        save()
    cp = read_text(f"{build_path_str}/{proj_part}/target/c4classpath")
    run(("java", "-cp", cp, g_main, "--ver", g_sum, "--context", build_path_str))


def two_col_str(d):
    max_len = max(len(l) for l, r in d) if len(d) > 0 else 0
    return "\n".join(f"{l}{' '*(max_len-len(l))} : {r}" for l, r in d)


def generate_configs(build_path_str, tmp_part, conf_plain):
    build_path = pathlib.Path(build_path_str)
    conf = {
        k: group_map(l, lambda it: (it[0],it[1]))
        for k, l in group_map(conf_plain, lambda it: (it[0],it[1:])).items()
    }
    full_dep = lazy_dict(lambda mod,get: sorted({
        mod, *(d for dep in get_list(conf,"C4DEP",mod) for d in get(dep))
    }))
    full_dep_edges = lazy_dict(lambda mod,get: {
        rel for to in get_list(conf,"C4DEP",mod) for rel in ((mod,to), *get(to))
    })
    full_dep_paths = lazy_dict(lambda a_mod, get: {
        c_mod: (*min(reasons), a_mod) for c_mod, reasons in group_map((
            p for to in get_list(conf, "C4DEP", a_mod) for p in ((to, ()), *get(to).items())
        ), lambda it: it).items()
    })
    dep_reasoning_prefix = one(*flat_values(conf.get("C4DEP_REASONING_PREFIX", {"#": [None]})))
    mod_heads = sorted({
        *(parse_main(main)["mod"] for main in flat_values(conf["C4TAG"])),
        *flat_values(conf["C4GENERATOR_MAIN"])
    })
    debug = """println("AvailableProcessors",java.lang.Runtime.getRuntime.availableProcessors);"""
    sbt_common_text = "".join((
        """ThisBuild / scalaVersion := "2.13.11"\n\n""", #"coursierMaxIterations := 200\n\n" +
        """val c4build = taskKey[Unit]("c4 build")\n\n""",
        f"""c4build := {{{debug}IO.write(baseDirectory.value/"target/c4classpath",(main / Compile / fullClasspath).value.map(_.data).mkString(":"))}}\n\n"""
    ))
    def excl(k): return get_list(conf,"C4EXT_EXCL",k)
    def get_repo(k): return get_list(conf,"C4REPO",k)

    for mod in mod_heads:
        modules = full_dep(mod)
        if len(modules) != len({ to_id(m) for m in modules }):
            raise Exception("bad mod names")
        sbt_text = sbt_common_text + "".join(
            to_sbt_mod(
                group_id, "project", ",".join(group_deps), [leave_tmp(dir) for dir in get_src_dirs(conf,group_mods)],
                sorted({ dep for m in group_mods for dep in get_list(conf,"C4EXT",m) }),
                sorted({ leave_tmp(dep) for m in group_mods for dep in get_list(conf,"C4LIB",m) }),
                excl, get_repo, flat_values(conf.get("C4WART",{})),
            )
            for group_id, group_mods, group_deps in get_mod_groups_1(mod, full_dep_edges(mod), modules)
        )
        proj_part = f"{tmp_part}/mod.{mod}.d"
        build_sbt = f"{proj_part}/build.sbt"
        plugins_sbt = f"{proj_part}/project/plugins.sbt"
        write_changed(build_path / build_sbt, sbt_text)
        plugins_text = 'addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.1.3")'
        write_changed(build_path / plugins_sbt, plugins_text)
        write_changed(build_path / f"{proj_part}/c4modules", ":".join(modules))
        write_changed(build_path / f"{proj_part}/c4sync_paths.json", json.dumps([
            build_sbt, plugins_sbt, *get_src_dirs(conf,modules),
            *sorted({ dep for m in modules for dep in get_list(conf,"C4LIB",m) })
        ]))
        if dep_reasoning_prefix:
            write_changed(build_path / f"dep_reasoning/{mod}-generated.log", two_col_str([
                (c_mod, ' / '.join(r.replace(dep_reasoning_prefix, ".") for r in reason))
                for c_mod, reason in sorted(full_dep_paths(mod).items())
            ]))

    ide_sbt_text = sbt_common_text + to_sbt_mod(
        "main", """(project in file("."))""", "",
        flat_values(conf["C4SRC"]), flat_values(conf["C4EXT"]), [],
        excl, get_repo, []
    )
    write_changed(build_path / "c4gen-generator.sbt", ide_sbt_text)

    out_conf = {
        "plain": conf_plain,
        "tag_info": {
            tag: parse_main(*mains)
            for tag, mains in conf["C4TAG"].items()
        },
        "allow_pkg_dep": {
            pkg: sorted({ get_pkg_from_mod(dep_mod) for mod in mod_list for dep_mod in full_dep(mod) }-{pkg})
            for pkg, mod_list in group_map(
                conf["C4DEP"].keys(), lambda mod: (get_pkg_from_mod(mod),mod)
            ).items()
        },
    }
    write_changed(build_path / f"{tmp_part}/build.json", json.dumps(out_conf, sort_keys=True, indent=4))
    # no src_dirs_generator_off
    src_dirs_generator_off = [
        ["C4GENERATOR_DIR_MODE", "OFF", s_dir]
        for mod in get_list(conf, "C4GENERATOR_MODE", "OFF")
        for s_dir in get_src_dirs(conf, full_dep(mod))
    ]
    generator_conf_keys = {"C4SRC", "C4PUB", "C4DEP", "C4TAG"}
    generator_conf = [*(line for line in conf_plain if line[0] in generator_conf_keys), *src_dirs_generator_off]
    for line in generator_conf:
        if len(line) != 3 or any(("\n" in it) for it in line):
            raise Exception(f"bad line {line}")
    generator_conf_str = "\n".join(it for line in generator_conf for it in line)
    write_changed(build_path / f"{tmp_part}/generator.conf", generator_conf_str)


def get_mod_groups_1(mod, deps, modules):
    def reduce_deps(replaces, more):
        replace = lambda k: replace(replaces[k]) if k in replaces else k
        replaced_deps = { (f,t) for f, t in ((replace(f),replace(t)) for f, t in deps) if f != t }
        if more <= 0:
            return (replace, replaced_deps)
        revs = group_map(replaced_deps, lambda ft: (ft[1],ft[0]))
        single_from_set = { one(*fs) for t, fs in revs.items() if len(fs)==1 }
        add_replaces = { t: one(*fs) for t, fs in revs.items() if len(fs)==1 and t not in single_from_set }
        return reduce_deps({**replaces, **add_replaces}, more - 1)
    replace, replaced_deps = reduce_deps({},2)
    #print(replaced_deps)
    rels = group_map(replaced_deps, lambda ft: ft)
    groups = group_map(modules, lambda m: (replace(m),m))
    return (*((
        "main" if group_id == mod else to_id(group_id),
        sorted(groups[group_id]),
        sorted(to_id(d) for d in rels.get(group_id, []))
    ) for group_id in sorted(groups.keys())),)


main(*sys.argv[1:])

#ThisBuild / exportJars := true

### mod 1-to-1:
# def get_mod_groups_0(mod, deps, modules):
#     rels = group_map(deps, lambda ft: ft)
#     return *((
#         "main" if m == mod else to_id(m),
#         (m,),
#         sorted(to_id(d) for d in rels.get(m, []))
#     ) for m in modules),
#

### mod stage-groups:
#fine_mod_stage = lazy_dict(lambda mod,get: max((0,*(get(dep)+1 for dep in get_list(conf,"C4DEP",mod)))))
#def mod_stage(mod): return fine_mod_stage(mod) // 4
#max_stage_num = mod_stage(mod)
# sbt_text = sbt_common_text + "".join(
#     to_sbt_mod(
#         "main" if stage_num==max_stage_num else f"s{stage_num}",
#         "project",
#         "" if stage_num==0 else f"s{stage_num-1}",
#         [leave_tmp(dir) for dir in get_src_dirs(conf,mods)],
#         sorted({
#             dep
#             for mod in mods for dep in get_list(conf,"C4EXT",mod)
#         }),
#         sorted({
#             leave_tmp(dep)
#             for mod in mods for dep in get_list(conf,"C4LIB",mod)
#         }),
#         excl, get_repo,
#         flat_values(get_dict(conf,"C4WART")),
#     )
#     for stage_num in range(max_stage_num+1)
#     for mods in [[m for m in modules if mod_stage(m)==stage_num]]
# )

### mod one-to-one:
# sbt_text = sbt_common_text + "".join(
#     to_sbt_mod(
#         "main" if m == mod else to_id(m),
#         "project",
#         ",".join(to_id(dep) for dep in get_list(conf,"C4DEP",m)),
#         [leave_tmp(dir) for dir in get_src_dirs(conf,(m,))],
#         get_list(conf,"C4EXT",m),
#         ? get_list(conf,"C4LIB",m),
#         excl, get_repo,
#         flat_values(get_dict(conf,"C4WART")),
#     )
#     for m in modules
# )
