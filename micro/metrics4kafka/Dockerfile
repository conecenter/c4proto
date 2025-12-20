FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates tini python3 openjdk-21-jdk-headless
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier && chmod +x /tools/coursier
USER c4
ENV PATH=${PATH}:/tools
RUN coursier fetch --classpath org.apache.kafka:kafka-clients:3.7.1 > /c4/kafka-clients-classpath
ENTRYPOINT ["tini","--","python3","-u","/app/main.py"]
COPY . /app
