
FROM ghcr.io/conecenter/c4replink:v2 AS c4downloader
USER root
RUN perl install.pl apt curl ca-certificates

FROM c4downloader AS c4tools-jdk
RUN /install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz
#RUN /install.pl curl https://download.oracle.com/java/17/latest/jdk-17_linux-x64_bin.tar.gz"
#RUN /install.pl curl https://download.bell-sw.com/java/17.0.2+9/bellsoft-jdk17.0.2+9-linux-amd64.tar.gz

FROM c4downloader AS c4tools-coursier
RUN /install.pl curl https://git.io/coursier-cli-linux \
 && chmod +x /tools/coursier

FROM c4downloader AS c4tools-sbt
RUN /install.pl curl https://github.com/sbt/sbt/releases/download/v1.6.2/sbt-1.6.2.tgz

FROM c4downloader AS c4tools-node
RUN perl install.pl apt xz-utils
RUN /install.pl curl https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz

FROM c4downloader AS c4tools-kafka
RUN /install.pl curl https://downloads.apache.org/kafka/3.1.0/kafka_2.13-3.1.0.tgz

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
  libjson-xs-perl python \
  python3-pip \
  python3.8 '#subprocess' \
  haproxy \
  uuid-runtime '#kafka cert gen' \
  mc lsof '#debug'
RUN pip3 install setuptools supervisor \
 && ln -s /c4/supervisord.conf /etc/supervisord.conf \
 && mkdir /c4repo && chown c4:c4 /c4repo
USER c4
COPY --chown=c4:c4 --from=c4tools-jdk /tools/* /tools/jdk
COPY --chown=c4:c4 --from=c4tools-coursier /tools/* /tools/coursier
COPY --chown=c4:c4 --from=c4tools-sbt /tools/* /tools/sbt
COPY --chown=c4:c4 --from=c4tools-node /tools/* /tools/node
COPY --chown=c4:c4 --from=c4tools-kafka /tools/* /tools/kafka
COPY --chown=c4:c4 --from=c4tools-minio /tools/* /tools/minio
COPY --chown=c4:c4 --from=c4tools-mcl /tools/* /tools/mcl
ENV PATH=${PATH}:/tools/jdk/bin:/tools:/tools/node/bin:/tools/kafka/bin:/tools/sbt/bin
COPY init.pl /
ENTRYPOINT ["perl","/init.pl"]
