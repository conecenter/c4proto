
import sys

def write_text(path, content):
    with open(path,"w") as f: f.write(content)

def gen_conf(context,path,comment,uid,home,setup,arch_name,repo_name,network):
    full_context = f"{home}/{repo_name}/{context}"
    write_text(f"{context}/{path}", "\n".join((
        f"{comment}### THIS FILE IS GENERATED ###",
        "docker stop c4agent_kc",
        "docker rm c4agent_kc",
        *setup,
        " && ".join([
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
        "FROM ubuntu:20.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /",
        "ARG C4UID",
        "RUN perl install.pl useradd $C4UID",
        "RUN perl install.pl apt curl ca-certificates libjson-xs-perl openssh-client rsync lsof python3 python3.8 openjdk-17-jre-headless git",
        "RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.8.0/sbt-1.8.0.tgz",
        f"RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/{arch_name}/kubectl && chmod +x /tools/kubectl",
        "RUN curl -L -o /t.tgz https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz"+
        " && tar -C /tools -xzf /t.tgz crane && rm /t.tgz",
        "USER c4",
        "ENV PATH=${PATH}:/c4/bin:/tools:/tools/sbt/bin",
    )))

def main(repo_name):
    context = "agent"
    ports = "-p 127.0.0.1:1979:1979 -p 127.0.0.1:4005:4005 -e C4AGENT_IP=0.0.0.0"
    gen_conf(context,"up"    ,"","$(id -u)","$HOME/c4repo",["mkdir -p $HOME/c4repo"],"amd64",repo_name,"--network host -e C4AGENT_IP=127.0.0.1")
    gen_conf(context,"up-arm","","$(id -u)","$HOME/c4repo",["mkdir -p $HOME/c4repo"],"arm64",repo_name,ports)
    gen_conf(context,"up.bat","REM ","1000","%c4repo-path%",["set c4repo-path=c:/c4repo","REM change c4repo-path to your own"],"amd64",repo_name,ports)

script, *args = sys.argv
main(*args)