
import subprocess
from pathlib import Path
from sys import argv

def run(args, **opt): return subprocess.run(args, check=True, **opt)
def write_text(path, text):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8", errors="strict")
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')

def gen_conf(path,comment,line_wrap,uid,home,setup,arch_name,network):
    write_text(path, "\n".join((
        f"{comment}### THIS FILE IS GENERATED ###",
        "docker stop c4agent_kc",
        "docker rm c4agent_kc",
        *setup,
        f" {line_wrap}\n && ".join([
            f"docker build -t c4agent_kc --build-arg C4UID={uid} --build-arg C4CPU_ARCH={arch_name} .",
            f"docker run -d -t --restart unless-stopped --init {network} -v {home}:/c4repo --name c4agent_kc c4agent_kc",
        ])
    )))

def perl_exec(line): return "\n".join(('#!/usr/bin/perl', 'use strict;', line, 'die'))

def main(to):
    write_text(f"{to}/Dockerfile", "\n".join((
        "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /",
        "ARG C4UID",
        "ARG C4CPU_ARCH",
        "RUN perl install.pl useradd $C4UID",
        "RUN perl install.pl apt curl ca-certificates libjson-xs-perl openssh-client rsync lsof python3 openjdk-17-jre-headless git micro",
        "RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.9.3/sbt-1.9.3.tgz",
        "RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/$C4CPU_ARCH/kubectl && chmod +x /tools/kubectl",
        "RUN curl -L -o /t.tgz https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz" +
        " && tar -C /tools -xzf /t.tgz crane && rm /t.tgz",
        "USER c4",
        "ENV PATH=${PATH}:/c4/bin:/tools:/tools/sbt/bin:/tools/linux",
        "ENV KUBECONFIG=/c4/.kube/config",
        "ENV KUBE_EDITOR=micro",
    )))
    #
    ports = "-p 127.0.0.1:1979:1979 -p 127.0.0.1:4005:4005 -e C4AGENT_IP=0.0.0.0"
    setup = ["mkdir -p $HOME/c4repo $HOME/bin", "cp host/bin/* $HOME/bin"]
    gen_conf(f"{to}/up"    ,"### ","\\","$(id -u)","$HOME/c4repo",setup,"amd64","--network host -e C4AGENT_IP=127.0.0.1")
    gen_conf(f"{to}/up-mac","### ","\\","$(id -u)","$HOME/c4repo",setup,"arm64",ports)
    gen_conf(f"{to}/up.bat","REM ","^","1000","%c4repo-path%",["set c4repo-path=c:/c4repo","REM change c4repo-path to your own"],"amd64",ports)
    #
    bin = f"{to}/c4/bin"
    host_bin = f"{to}/host/bin"
    parse_exec = f'my($ctx,$pod)=$c4pod=~/(.+)~(.+)/?($1,$2):die; exec "kubectl", "--context", $ctx,'
    parse_exec2 = f'{parse_exec} "exec", "-i", $pod,'
    write_text(f"{bin}/c4rsh_raw", perl_exec(f'my($c4pod,@args)=@ARGV; {parse_exec2} "--", @args;')) # code
    write_text(f"{bin}/c4dsync", perl_exec('exec "rsync","--blocking-io","-e","c4rsh_raw",@ARGV;')) # code
    write_text(f"{bin}/c4rsh", perl_exec(f'my $c4pod = scalar(`cat /tmp/c4pod`)||die "no pod"; {parse_exec2} "-t", "--", "bash";')) # manual only
    write_text(f"{bin}/de", perl_exec(f'my $c4pod = scalar(`cat /tmp/c4pod`)||die "no pod"; {parse_exec2} "--", @ARGV;')) # manual only
    write_text(f"{bin}/kc", perl_exec(f'exec "kubectl", "--context", @ARGV;')) # manual only
    write_text(f"{bin}/c4forward", perl_exec(f'my($ip,$c4pod)=@ARGV; {parse_exec} "port-forward", "--address", $ip, $pod, "4005";')) # code
    agent_dir = str(Path(__file__).parent)
    proto_dir = str(Path(__file__).parent.parent)
    write_text(f"{bin}/c4ci_prep", read_text(f"{proto_dir}/ci_prep.py"))
    write_text(f"{bin}/c4ci_up", read_text(f"{proto_dir}/ci_up.py"))
    write_text(f"{bin}/c4sw", read_text(f"{agent_dir}/sw.py"))
    write_text(f"{bin}/cio_call", read_text(f"{agent_dir}/cio.py"))
    write_text(f"{host_bin}/a4", perl_exec('exec "docker", "exec", "-i", "c4agent_kc", @ARGV;'))
    write_text(f"{host_bin}/a4t", perl_exec('exec "docker", "exec", "-it", "c4agent_kc", @ARGV;'))
    run(("sh","-c",f"chmod +x {to}/up* {bin}/* {host_bin}/*"))

main(*argv[1:])
