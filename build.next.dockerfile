ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
COPY --chown=c4:c4 . /c4/c4proto
RUN cd /c4/c4proto && ./app.pl build_some_server
ARG C4CI_FULL_IMG
RUN perl /c4/c4proto/prod.pl ci_cp_proto ${C4CI_FULL_IMG} /c4/c4proto