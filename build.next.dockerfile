ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
RUN rm -r /c4repo/c4proto
#
COPY --chown=c4:c4 . /c4repo/c4proto
RUN perl /c4repo/c4proto/sync.pl start /c4repo/c4proto /c4/c4proto 0
RUN perl /c4/c4proto/prod.pl ci_inner_build
RUN ["/c4/c4proto/compile"]
RUN perl /c4/c4proto/prod.pl ci_inner_cp
