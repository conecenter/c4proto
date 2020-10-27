FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip libyaml-libyaml-perl libjson-xs-perl rsync python zip
RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.5%2B10/OpenJDK11U-jdk_x64_linux_hotspot_11.0.5_10.tar.gz
RUN perl install.pl curl https://git.io/coursier-cli-linux && chmod +x /tools/coursier
RUN perl install.pl curl https://nodejs.org/dist/v8.9.1/node-v8.9.1-linux-x64.tar.xz
USER c4
ENV PATH=${PATH}:/tools/jdk/bin:/tools:/tools/node/bin:/c4/wrap
RUN coursier install bloop:1.4.4 --only-prebuilt=true
ARG C4CI_BASE_TAG
ENV C4CI_BASE_TAG_ENV=$C4CI_BASE_TAG
ENV C4CI_PROTO_DIR=/c4/c4proto
ENV C4CI_BUILD_DIR=/c4/c4proto
#
COPY --chown=c4:c4 . /c4repo/c4proto
RUN perl /c4repo/c4proto/sync.pl start /c4repo/c4proto /c4/c4proto 0
RUN perl /c4/c4proto/prod.pl ci_inner_build
RUN perl /c4/c4proto/prod.pl ci_inner_cp