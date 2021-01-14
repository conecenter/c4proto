ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
RUN rm -r /c4/cause
#
COPY --chown=c4:c4 . /c4/cause
RUN perl /c4/cause/sync.pl start /c4/cause $C4CI_BUILD_DIR 0 \
 && /replink.pl
RUN perl $C4CI_PROTO_DIR/prod.pl ci_inner_build
RUN perl $C4CI_PROTO_DIR/prod.pl ci_inner_cp
