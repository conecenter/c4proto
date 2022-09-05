FROM ghcr.io/conecenter/c4replink:v2
USER root
RUN /install.pl apt curl ca-certificates '#certs for curl' libjson-xs-perl openssh-client rsync lsof python3
RUN /install.pl curl https://storage.googleapis.com/kubernetes-release/release/v1.21.0/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
USER c4
ENV PATH=${PATH}:/tools:/c4/bin
