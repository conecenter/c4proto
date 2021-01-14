FROM builder:replink-with-keys-v2
USER root
RUN /install.pl apt \
    curl ca-certificates xz-utils \
    libyaml-libyaml-perl libjson-xs-perl python \
    rsync zip
RUN /install.pl curl https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.5%2B10/OpenJDK11U-jdk_x64_linux_hotspot_11.0.5_10.tar.gz
RUN /install.pl curl https://git.io/coursier-cli-linux && chmod +x /tools/coursier
USER c4
ENV PATH=${PATH}:/tools/jdk/bin:/tools:/c4/.bloop
RUN curl -L https://github.com/scalacenter/bloop/releases/download/v1.3.4/install.py | python
ARG C4CI_BASE_TAG
ENV C4CI_BASE_TAG_ENV=$C4CI_BASE_TAG
ENV C4CI_PROTO_DIR=/c4/repo/main
ENV C4CI_BUILD_DIR=/c4/repo/main
ENV C4REPO_MAIN_CONF=$C4CI_BUILD_DIR/c4dep.main.replink
ENV C4STEP_SYNC="mkdir -p $C4CI_BUILD_DIR && cp /c4/cause/*.replink /c4/cause/*.pl $C4CI_BUILD_DIR/  && /replink.pl && perl $C4CI_PROTO_DIR/sync.pl start /c4/cause $C4CI_BUILD_DIR 0"
ENV C4STEP_BUILD="perl $C4CI_PROTO_DIR/prod.pl ci_inner_build"
ENV C4STEP_BUILD_CLIENT="echo NOOP"
ENV C4STEP_COPY="perl $C4CI_PROTO_DIR/prod.pl ci_inner_cp"
#
COPY --chown=c4:c4 . /c4/cause
RUN eval $C4STEP_SYNC
RUN $C4STEP_BUILD
RUN $C4STEP_BUILD_CLIENT
RUN $C4STEP_COPY
