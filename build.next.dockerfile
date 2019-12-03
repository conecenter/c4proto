ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
COPY --chown=c4:c4 . /c4repo/c4proto
RUN perl /c4repo/c4proto/prod.pl ci_build_inner /c4repo/c4proto /c4/c4proto