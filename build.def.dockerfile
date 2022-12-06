
ARG C4UID=1979
FROM ghcr.io/conecenter/c4replink:v2
USER root
RUN /install.pl apt \
    curl ca-certificates xz-utils '#build base' \
    libjson-xs-perl python '#build base' \
    zip rsync '#build final-copy' \
    make g++ '#build client (sass)' \
    fontconfig locales '#?some for runtime' \
    lsof mc netcat-openbsd '#debug base' \
    haproxy '#sandbox' \
    uuid-runtime '#sandbox->ci' \
    rsync openssh-client '#sandbox remote' \
    atop less bash-completion tmux '#debug more' \
    python3 '#snapshot_put' \
    python3-pip '#pip3 install' \
    python3.8 '#qa-subprocess-text-capture_output' \
    '#1'
# build tools:
RUN /install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz
RUN /install.pl curl https://git.io/coursier-cli-linux && chmod +x /tools/coursier
RUN /install.pl curl https://github.com/sbt/sbt/releases/download/v1.6.2/sbt-1.6.2.tgz
RUN /install.pl curl https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz
# sandbox tools/fixes:
RUN /install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
RUN /install.pl curl https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.7/async-profiler-2.7-linux-x64.tar.gz
RUN /install.pl curl https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz
RUN pip3 install python-gitlab setuptools supervisor
RUN echo en_DK.UTF-8 UTF-8 >> /etc/locale.gen && locale-gen
USER c4
ENV PATH=${PATH}:/usr/local/bin:/tools/jdk/bin:/tools:/tools/node/bin:/tools/sbt/bin:/tools/apache/bin:/c4/bin
ENV JAVA_HOME=/tools/jdk
# pre-installing just to optimize:
RUN mkdir -p /c4/c4client_prep && cd /c4/c4client_prep && npm install node-sass@4.13.1
# setup build steps:
ENV C4CI_BUILD_DIR=$C4CI_BUILD_DIR_ARG
ENV C4CI_PROTO_DIR=/c4/c4proto
ENV C4STEP_BUILD_CLIENT="perl $C4CI_BUILD_DIR/ci-step.pl client"
# some common parts for projects may be moved here, before C4CI_BASE_TAG and COPY
COPY --chown=c4:c4 . $C4CI_PROTO_DIR
RUN perl $C4CI_PROTO_DIR/sync_setup.pl
RUN echo 'exec "bash", @ARGV; die' > /c4/c4serve.pl
ENTRYPOINT ["perl","/c4/c4serve.pl"]