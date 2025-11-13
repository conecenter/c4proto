FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates tini python3 openssh-client
USER c4
ENTRYPOINT ["tini","--","python3","-u","/app/main.py"]