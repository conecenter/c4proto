FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates python3 tini netcat-openbsd
RUN /install.pl curl https://dl.min.io/client/mc/release/linux-amd64/mc && chmod +x /tools/mc
RUN /install.pl curl https://download.bell-sw.com/java/21.0.4+9/bellsoft-jdk21.0.4+9-linux-amd64.tar.gz
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier && chmod +x /tools/coursier
USER c4
ENV PATH=${PATH}:/tools:/tools/jdk/bin
RUN coursier fetch --classpath org.apache.kafka:kafka-clients:3.7.1 > /c4/kafka-clients-classpath
ENTRYPOINT ["tini","--","python3","-u","/app/main.py","serve"]
