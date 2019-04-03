FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip
RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz
RUN perl install.pl curl https://piccolo.link/sbt-1.2.8.tgz
RUN perl install.pl apt libyaml-libyaml-perl
ENV PATH=${PATH}:/tools/jdk/bin:/tools/sbt/bin
USER c4
COPY --chown=c4:c4 . /c4/c4proto
RUN cd /c4/c4proto && ./app.pl build_some_server
ARG C4CI_FULL_IMG
RUN perl /c4/c4proto/prod.pl ci_cp_proto ${C4CI_FULL_IMG} /c4/c4proto
