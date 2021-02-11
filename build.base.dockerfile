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
ARG C4CI_BASE_TAG
ENV C4CI_BASE_TAG_ENV=$C4CI_BASE_TAG
ENV C4CI_BUILD_DIR=/c4/main
ENV C4CI_PROTO_DIR=$C4CI_BUILD_DIR
ENV C4REPO_MAIN_CONF=$C4CI_BUILD_DIR/c4dep.main.replink
ENV C4STEP_SYNC="find $C4CI_BUILD_DIR >$C4CI_BUILD_DIR/ci.rm && /replink.pl"
ENV C4STEP_BUILD="perl $C4CI_PROTO_DIR/prod.pl ci_inner_build"
ENV C4STEP_BUILD_CLIENT="echo NOOP"
ENV C4STEP_COPY="perl $C4CI_PROTO_DIR/prod.pl ci_inner_cp"
#
COPY --chown=c4:c4 . /c4/main
RUN eval $C4STEP_SYNC
RUN $C4STEP_BUILD
RUN $C4STEP_BUILD_CLIENT
RUN $C4STEP_COPY
