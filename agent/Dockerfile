FROM ghcr.io/conecenter/c4replink:v2
USER root
RUN perl install.pl apt curl ca-certificates '#certs for curl' libjson-xs-perl openssh-client rsync lsof python3 python3.8
RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz
RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.7.1/sbt-1.7.1.tgz
RUN perl install.pl curl https://github.com/sbt/sbt/releases/download/v1.8.0/sbt-1.8.0.tgz
RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
USER c4
ENV PATH=${PATH}:/c4/bin:/tools:/tools/jdk/bin:/tools/sbt/bin
ENV JAVA_HOME=/tools/jdk
