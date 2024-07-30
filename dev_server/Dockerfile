
FROM ubuntu:22.04 AS c4downloader
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /
RUN perl install.pl apt curl ca-certificates
ONBUILD ARG C4UID
ONBUILD RUN /install.pl useradd $C4UID

FROM c4downloader AS c4tools-coursier
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier
RUN chmod +x /tools/coursier

FROM c4downloader AS c4tools-sbt
RUN /install.pl curl https://github.com/sbt/sbt/releases/download/v1.9.3/sbt-1.9.3.tgz

FROM c4downloader AS c4tools-node
RUN perl install.pl apt xz-utils
RUN /install.pl curl https://nodejs.org/dist/v20.5.0/node-v20.5.0-linux-x64.tar.xz

FROM c4downloader AS c4tools-kafka
RUN /install.pl curl https://downloads.apache.org/kafka/3.7.1/kafka_2.13-3.7.1.tgz

FROM c4downloader AS c4tools-minio
RUN /install.pl curl https://dl.min.io/server/minio/release/linux-amd64/minio \
 && chmod +x /tools/minio

FROM c4downloader AS c4tools-mcl
RUN /install.pl curl https://dl.min.io/client/mc/release/linux-amd64/mc \
 && chmod +x /tools/mc \
 && mv /tools/mc /tools/mcl

#? software-properties-common

FROM c4downloader
RUN perl /install.pl apt \
  libjson-xs-perl python3 git rsync python3-pip openjdk-17-jdk-headless haproxy \
  uuid-runtime '#kafka cert gen' \
  mc lsof '#debug'
RUN pip3 install setuptools supervisor \
 && ln -s /c4/supervisord.conf /etc/supervisord.conf \
 && mkdir /c4repo && chown c4:c4 /c4repo
USER c4
COPY --chown=c4:c4 --from=c4tools-coursier /tools/* /tools/coursier
COPY --chown=c4:c4 --from=c4tools-sbt /tools/* /tools/sbt
COPY --chown=c4:c4 --from=c4tools-node /tools/* /tools/node
COPY --chown=c4:c4 --from=c4tools-kafka /tools/* /tools/kafka
COPY --chown=c4:c4 --from=c4tools-minio /tools/* /tools/minio
COPY --chown=c4:c4 --from=c4tools-mcl /tools/* /tools/mcl
ENV PATH=${PATH}:/tools:/tools/node/bin:/tools/kafka/bin:/tools/sbt/bin
COPY init.pl /
ENTRYPOINT ["perl","/init.pl"]
