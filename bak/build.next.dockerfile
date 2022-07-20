ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
#
COPY --chown=c4:c4 . /c4/main
RUN eval $C4STEP_SYNC
RUN $C4STEP_BUILD
RUN $C4STEP_BUILD_CLIENT
RUN $C4STEP_COPY