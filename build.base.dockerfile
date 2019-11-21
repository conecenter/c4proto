FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip libyaml-libyaml-perl
#RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz
RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.5%2B10/OpenJDK11U-jdk_x64_linux_hotspot_11.0.5_10.tar.gz
RUN perl install.pl curl https://piccolo.link/sbt-1.3.2.tgz
ENV PATH=${PATH}:/tools/jdk/bin:/tools/sbt/bin
USER c4
RUN perl install.pl sbt 2.13.0 1.3.2
COPY --chown=c4:c4 . /c4/c4proto
RUN cd /c4/c4proto && ./app.pl build_some_server
ARG C4CI_BASE_TAG
RUN perl /c4/c4proto/prod.pl ci_cp_proto ${C4CI_BASE_TAG} /c4/c4proto
