FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates
RUN /install.pl curl https://dl.min.io/client/mc/release/linux-amd64/mc && chmod +x /tools/mc
ENTRYPOINT /tools/mc alias set def $(cat $C4S3_CONF_DIR/address) $(cat $C4S3_CONF_DIR/key) $(cat $C4S3_CONF_DIR/secret) && exec sleep infinity
