FROM ubuntu:20.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /
RUN perl install.pl useradd 1000
RUN perl install.pl apt curl ca-certificates libjson-xs-perl openssh-client rsync lsof python3 python3.8 openjdk-17-jre-headless git
RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.8.0/sbt-1.8.0.tgz
RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/arm64/kubectl && chmod +x /tools/kubectl
USER c4
ENV PATH=${PATH}:/c4/bin:/tools:/tools/sbt/bin
