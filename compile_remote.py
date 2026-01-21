
from json import loads
from pathlib import Path
from subprocess import check_output, run
from time import sleep

from c4util import da, changing_observe, parse_classpath, get_more_compile_options, parse_args

def perl_exec(*lines): return "\n".join(('#!/usr/bin/perl', 'use strict;', *lines, 'die;'))

def c4dsync(kube_ctx, pod):
    rsh_raw = f"/tmp/c4rsh_raw-{kube_ctx}"
    content = perl_exec(
        f'my ($pod,@args) = @ARGV;',
        f'exec "kubectl", "--context", "{kube_ctx}", "exec", "-i", $pod, "--", @args;'
    )
    for save in changing_observe(Path(rsh_raw), content.encode()):
        save()
        check_output(da("chmod", "+x", rsh_raw))
    fr = ("rsync", "--blocking-io", "-e", rsh_raw, "-cr", "--del", "--files-from", "-", f"{pod}:/", "/")
    to = ("rsync", "--blocking-io", "-e", rsh_raw, "-acr", "--del", "--files-from", "-", "/", f"{pod}:/")
    return fr, to
#"--log-file=/tmp/rsync.log", "-i", "-v", "--progress", "--stats",

def file_list(files): return "".join(f"{f}\n" for f in files).encode()

def remote_compile(kube_context, context, pod, proj_tag):
    tag_info, mod_dir, sbt_args, ok_path = get_more_compile_options(context, proj_tag)
    cp_path = f"{mod_dir}/target/c4classpath"
    ex = ("kubectl", "--context", kube_context, "exec", pod, "--")
    for _ in range(12):
        if run(da(*ex, "true"), check=False).returncode == 0: break
        da("waiting a minute for pod ...")
        sleep(5)
    full_sync_paths = [f"{context}/{part}" for part in loads(Path(f"{mod_dir}/c4sync_paths.json").read_bytes())]
    fr_cmd, to_cmd = c4dsync(kube_context, pod)
    check_output(da(*to_cmd), input=file_list(path for path in full_sync_paths if Path(path).exists()))
    check_output(da(*ex, *sbt_args))
    check_output(da(*fr_cmd), input=file_list([cp_path]))
    check_output(da(*fr_cmd), input=file_list(parse_classpath(Path(cp_path).read_bytes().decode())))

remote_compile(**parse_args(("--kube-context", "--context", "--pod", "--proj-tag")))
