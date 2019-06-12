ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
COPY --chown=c4:c4 . /c4/c4proto
RUN cd /c4/c4proto && ./app.pl build_some_server
RUN perl /c4/c4proto/prod.pl ci_cp_proto def /c4/c4proto