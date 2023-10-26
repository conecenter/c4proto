FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN /install.pl apt \
    curl ca-certificates xz-utils '#build base' \
    libjson-xs-perl python3 '#build base' \
    zip rsync '#build final-copy' \
    fontconfig locales '#?some for runtime' \
    lsof mc netcat-openbsd '#debug base' \
    haproxy '#sandbox' \
    uuid-runtime '#sandbox->ci' \
    rsync openssh-client '#sandbox remote' \
    atop less bash-completion tmux '#debug more' \
    python3-pip '#pip3 install'
# build tools:
RUN /install.pl curl https://download.bell-sw.com/java/17.0.8+7/bellsoft-jdk17.0.8+7-linux-amd64.tar.gz
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier && chmod +x /tools/coursier
RUN /install.pl curl https://github.com/sbt/sbt/releases/download/v1.9.3/sbt-1.9.3.tgz
#RUN /install.pl curl https://nodejs.org/dist/v20.5.0/node-v20.5.0-linux-x64.tar.xz
RUN /install.pl curl https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz
# sandbox tools/fixes:
RUN /install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
RUN /install.pl curl https://get.helm.sh/helm-v3.12.1-linux-amd64.tar.gz
RUN /install.pl curl https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.7/async-profiler-2.7-linux-x64.tar.gz
RUN curl -L -o /t.tgz https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz \
 && tar -C /tools -xzf /t.tgz crane && rm /t.tgz # install fails on L* file
RUN pip3 install setuptools supervisor
RUN echo en_DK.UTF-8 UTF-8 >> /etc/locale.gen && locale-gen
USER c4
ENV PATH=${PATH}:/usr/local/bin:/tools/jdk/bin:/tools:/tools/node/bin:/tools/sbt/bin:/tools/apache/bin:/c4/bin
ENV JAVA_HOME=/tools/jdk
# setup build steps:
RUN echo 'exec "bash", @ARGV; die' > /c4/c4serve.pl
ENTRYPOINT ["perl","/c4/c4serve.pl"]
