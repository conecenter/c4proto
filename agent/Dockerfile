FROM ghcr.io/conecenter/c4replink:v2
USER root
RUN /install.pl apt curl ca-certificates '#certs for curl' libjson-xs-perl openssh-client rsync lsof python3
RUN /install.pl curl https://storage.googleapis.com/kubernetes-release/release/v1.21.0/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
RUN /install.pl curl https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz
USER c4
ENV PATH=${PATH}:/tools
