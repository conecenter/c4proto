FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip
RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz
RUN perl install.pl curl https://piccolo.link/sbt-1.2.8.tgz
RUN perl install.pl apt libyaml-libyaml-perl
COPY . /c4/c4proto
RUN mkdir /c4res \
 && chown c4:c4 /c4res \
 && chown -R c4:c4 /c4
USER c4
ARG C4CI_FULL_IMG
RUN perl /c4/c4proto/prod.pl build_some_server ${C4CI_FULL_IMG} /c4/c4proto
