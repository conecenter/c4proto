
FROM scratch AS dl
ADD --link --unpack https://github.com/moby/buildkit/releases/download/v0.26.3/buildkit-v0.26.3.linux-amd64.tar.gz /tools
ADD --link --unpack https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz /tools

FROM ubuntu:22.04 AS u22
RUN useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4

FROM u22 AS ci
RUN apt update && DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    ca-certificates python3 git libjson-xs-perl \
 && rm -rf /var/lib/apt/lists/*
ADD --link --chmod=755 https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl /tools/kubectl
COPY --link --from=dl /tools/crane /tools/crane
COPY --link --from=dl /tools/bin/buildctl /tools/buildctl
COPY --from=c4emb /ci_deploy_info.pl /ci/ci_deploy_info.pl
COPY --from=c4emb /ci_build.py /ci/ci_build.py
COPY --from=c4emb /make_manifests.pl /ci/make_manifests.pl
COPY --from=c4emb /ci_prep.py /tools/c4ci_prep
COPY --from=c4emb /ci_up.py /tools/c4ci_up
RUN chmod +x /tools/c4ci_prep /tools/c4ci_up
ENV PATH=${PATH}:/tools

# ADD --link --unpack https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-linux-amd64.tar.gz /tools
# ENV PATH=${PATH}:/tools:/tools/linux

FROM u22 AS base
RUN apt update && DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    ca-certificates curl libjson-xs-perl python3 unzip zip rsync fontconfig locales python3-pip uuid-runtime \
    lsof mc netcat-openbsd atop less bash-completion tmux \
 && rm -rf /var/lib/apt/lists/*
# zip rsync - build final-copy; curl, unzip - replink; fontconfig locales - ?some for runtime ; uuid-runtime - sandbox->ci
# lsof mc netcat-openbsd atop less bash-completion tmux - debug
# build tools:
ADD --link --unpack "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-linux-amd64.tar.gz" /tools
ADD --link --chmod=755 https://github.com/coursier/launchers/raw/master/coursier /tools/coursier
ADD --link --unpack https://github.com/sbt/sbt/releases/download/v1.9.3/sbt-1.9.3.tgz /tools
ADD --link --unpack https://nodejs.org/dist/v20.9.0/node-v20.9.0-linux-x64.tar.xz /tools
# sandbox tools/fixes:
ADD --link --chmod=755 https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl /tools/kubectl
ADD --link --unpack https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.7/async-profiler-2.7-linux-x64.tar.gz /tools
RUN pip3 install setuptools supervisor
RUN echo en_DK.UTF-8 UTF-8 >> /etc/locale.gen && locale-gen
USER c4
ENV PATH=${PATH}:/tools:/tools/jdk-17.0.17/bin:/tools/sbt/bin:/tools/node-v20.9.0-linux-x64/bin:/tools/async-profiler-2.7-linux-x64
ENV JAVA_HOME=/tools/jdk-17.0.17
WORKDIR /c4
ENTRYPOINT ["perl","-e","exec 'bash', @ARGV; die"]

FROM base AS de
COPY --from=c4emb /replink.py /tools/replink.py
COPY --chown=c4:c4 . /c4/c4proj
ARG C4PROJECT
ARG C4COMMIT
RUN python3 -u /tools/replink.py --context /c4/c4proj --set-proto-dir /c4/c4proto --commit '${C4COMMIT}' --commits-out /c4/c4proj/target/c4repo_commits
RUN timeout 1800 python3 -u /c4/c4proto/build_rt.py --proj-tag ${C4PROJECT} --context /c4/c4proj --out /c4/c4res \
 || echo 'FULL BUILD FAILED'
ENTRYPOINT ["perl","/c4/c4proto/sandbox.pl","main","--context","/c4/c4proj","--proj-tag","${C4PROJECT}"]

FROM u22 AS rt
RUN apt update && DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    curl software-properties-common lsof mc iputils-ping netcat-openbsd fontconfig openssh-client python3 \
 && rm -rf /var/lib/apt/lists/*
# openssh-client for repl; python3 for vault and dig
ADD --link --unpack "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-linux-amd64.tar.gz" /tools
ENV PATH=${PATH}:/tools:/tools/jdk-17.0.17/bin
ENV JAVA_HOME=/tools/jdk-17.0.17
RUN chown -R c4:c4 /c4
WORKDIR /c4
USER c4
ENTRYPOINT ["perl","run.pl"]
COPY --link --from=de /c4/c4res/* /c4
RUN echo "descr#${C4COMMIT}" > /c4/c4ref_descr
# `git describe --all` seems depending on how we make checkout, it can be w/o commit and not good generally