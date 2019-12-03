ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
COPY --chown=c4:c4 . /c4repo/c4proto
RUN cd /c4repo/c4proto && ./app.pl build
RUN perl /c4/c4proto/prod.pl ci_cp_proto /c4/c4proto