ARG C4UID=1979
FROM ghcr.io/conecenter/c4replink:v2
USER root
RUN /install.pl apt \
    curl ca-certificates xz-utils \
    libjson-xs-perl python \
    zip
RUN /install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz
RUN /install.pl curl https://git.io/coursier-cli-linux && chmod +x /tools/coursier
USER c4
ENV PATH=${PATH}:/tools/jdk/bin:/tools:/c4/.bloop
RUN curl -L https://github.com/scalacenter/bloop/releases/download/v1.3.4/install.py | python
ENV C4CI_BUILD_DIR=/c4/main
ENV C4CI_PROTO_DIR=$C4CI_BUILD_DIR
ENV C4STEP_0="perl $C4CI_PROTO_DIR/prod.pl ci_inner_build"
ENV C4STEP_1="echo NOOP"
ENV C4STEP_2="perl $C4CI_PROTO_DIR/prod.pl ci_inner_cp"
ENV C4STEP_RM="perl $C4CI_PROTO_DIR/prod.pl ci_rm"
COPY --chown=c4:c4 . $C4CI_BUILD_DIR
RUN find $C4CI_BUILD_DIR >$C4CI_BUILD_DIR/ci.rm
