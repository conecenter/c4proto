FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
USER c4
ENTRYPOINT ["perl","-e","exec 'sleep','infinity';die"]