FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip
RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz
RUN perl install.pl curl https://piccolo.link/sbt-1.2.8.tgz
COPY . /c4/c4proto
RUN mkdir /c4res
 && chown c4:c4 /c4res
 && chown -R c4:c4 /c4
USER c4
RUN cd /c4/c4proto
 && ./app.pl build_some_server
 && perl prod.pl ci_prepare_runtime_build_context /c4/c4proto
