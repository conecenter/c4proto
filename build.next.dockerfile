ARG C4CI_BASE_TAG
FROM builder:$C4CI_BASE_TAG
RUN perl prod.pl build_some_server $C4CI_FULL_IMG /c4/c4proto