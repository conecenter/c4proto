
import sys
import pathlib

def read_text(path_str):
    return pathlib.Path(path_str).read_text(encoding='utf-8', errors='strict')

def write_text(path, content):
    with open(path,"w") as f: f.write(content)

def never(a): raise Exception(a)

def gen_conf(context,path,comment,line_wrap,uid,home,setup,arch_name,repo_name,network):
    full_context = f"{home}/{repo_name}/{context}"
    write_text(f"{context}/{path}", "\n".join((
        f"{comment}### THIS FILE IS GENERATED ###",
        "docker stop c4agent_kc",
        "docker rm c4agent_kc",
        *setup,
        f" {line_wrap}\n && ".join([
            f"docker build -t c4agent_kc --build-arg C4UID={uid} -f {full_context}/{path}.dockerfile {full_context}",
            " ".join([
                "docker run -d -t  --restart unless-stopped --init",
                network,
                f"-v {home}:/c4repo",
                f"-e C4CI_PROTO_DIR=/c4repo/{repo_name}/c4proto",
                "-e C4DEPLOY_CONTEXT=dev",
                "--name c4agent_kc",
                "c4agent_kc",
                f"sh /c4repo/{repo_name}/agent/agent.sh",
            ]),
        ])
    )))
    write_text(f"{context}/{path}.dockerfile", "\n".join((
        "### THIS FILE IS GENERATED ###",
        "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /",
        "ARG C4UID",
        "RUN perl install.pl useradd $C4UID",
        "RUN perl install.pl apt curl ca-certificates libjson-xs-perl openssh-client rsync lsof python3 openjdk-17-jre-headless git micro",
        "RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.9.3/sbt-1.9.3.tgz",
        f"RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/{arch_name}/kubectl && chmod +x /tools/kubectl",
        "RUN perl install.pl curl https://get.helm.sh/helm-v3.12.1-linux-amd64.tar.gz",
        "RUN curl -L -o /t.tgz https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz"+
        " && tar -C /tools -xzf /t.tgz crane && rm /t.tgz",
        "USER c4",
        "ENV PATH=${PATH}:/c4/bin:/tools:/tools/sbt/bin:/tools/linux",
        "ENV KUBECONFIG=/c4/.kube/config",
        "ENV KUBE_EDITOR=micro",
    )))

def gen_sync(context, path, repo_name, comment, line_wrap, run):
    replink = f"c4dep.ci.replink"
    rels = ("", *(
        local
        for row in read_text(replink).splitlines()
        for cmd, local_str, rem, comm in (row.split(),)
        for local, dummy in (local_str.split("/"),)
        if cmd == "C4REL" and dummy == "dummy" or never("bad rel")
    ))
    git_lines = [line for rel in rels for line in (
        f"git -C ./{rel} rev-parse HEAD > target/c4sync-head-{rel}",
        f"git -C ./{rel} status --porcelain=v1 --no-renames > target/c4sync-stat-{rel}",
    )]
    write_text(f"{context}/{path}", "\n".join((
        f"{comment}### THIS FILE IS GENERATED ###",
        f" {line_wrap}\n && ".join((
            f"docker exec -e C4REPO_MAIN_CONF=/c4repo/{repo_name}/{replink} c4agent_kc /replink.pl",
            *git_lines,
            "docker exec c4agent_kc c4py sync local" +
            f" --from-dir /c4repo/{repo_name} --to-dir /c4/{repo_name}" +
            f" --rels {':'.join(rels)}",
            *run
        ))
    )))

def main(repo_name):
    context = "agent"
    ports = "-p 127.0.0.1:1979:1979 -p 127.0.0.1:4005:4005 -e C4AGENT_IP=0.0.0.0"
    gen_conf(context,"up"    ,"### ","\\","$(id -u)","$HOME/c4repo",["mkdir -p $HOME/c4repo"],"amd64",repo_name,"--network host -e C4AGENT_IP=127.0.0.1")
    gen_conf(context,"up-arm","### ","\\","$(id -u)","$HOME/c4repo",["mkdir -p $HOME/c4repo"],"arm64",repo_name,ports)
    gen_conf(context,"up.bat","REM ","^","1000","%c4repo-path%",["set c4repo-path=c:/c4repo","REM change c4repo-path to your own"],"amd64",repo_name,ports)
    gen_sync(context,"sync.bat",repo_name,"REM ","^",[f"docker exec c4agent_kc c4run %*"])
    #gen_sync(context,"sync_test.sh",repo_name,"#!/bin/sh\n","\\",[])
    replink = f"c4dep.ci.replink"
    write_text(f"{context}/run", "\n".join((
        "#!/usr/bin/perl",
        f"system 'docker', 'exec', '-e', 'C4REPO_MAIN_CONF=/c4repo/{repo_name}/{replink}', 'c4agent_kc', '/replink.pl' and die;",
        f"""system 'docker','exec','c4agent_kc','perl','-e','"/c4repo/{repo_name}" eq readlink "/c4/{repo_name}" or symlink "/c4repo/{repo_name}", "/c4/{repo_name}" or die' and die;""",
        "exec 'docker','exec','c4agent_kc','c4run',@ARGV;",
        "die"
    )))

script, *args = sys.argv
main(*args)
