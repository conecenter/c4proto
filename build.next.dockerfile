ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
RUN rm -r /c4/repo/cause
#
COPY --chown=c4:c4 . /c4/repo/cause
RUN perl /c4/repo/cause/sync.pl start /c4/repo/cause $C4CI_BUILD_DIR 0
RUN perl $C4CI_PROTO_DIR/prod.pl ci_inner_build
RUN perl $C4CI_PROTO_DIR/prod.pl ci_inner_cp
