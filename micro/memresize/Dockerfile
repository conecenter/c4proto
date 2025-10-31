FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates python3 tini
RUN perl install.pl curl https://dl.k8s.io/release/v1.33.1/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
USER c4
ENV PATH=${PATH}:/tools
ENTRYPOINT ["tini","--","python3","-u","/app/main.py"]
