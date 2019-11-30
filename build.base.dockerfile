FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip libyaml-libyaml-perl libjson-xs-perl rsync
RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.5%2B10/OpenJDK11U-jdk_x64_linux_hotspot_11.0.5_10.tar.gz
RUN perl install.pl curl https://git.io/coursier-cli && chmod +x /tools/coursier
ENV PATH=${PATH}:/tools/jdk/bin:/tools/sbt/bin:/tools
USER c4
COPY --chown=c4:c4 . /c4repo/c4proto
RUN cd /c4repo/c4proto && ./app.pl build_some_server
ARG C4CI_BASE_TAG
RUN perl /c4/c4proto/prod.pl ci_cp_proto ${C4CI_BASE_TAG} /c4/c4proto
