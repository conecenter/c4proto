# dl.min.io returns 410 since mc was archived; build pinned release from source
FROM golang:1.24 AS mc
RUN CGO_ENABLED=0 GOBIN=/out go install github.com/minio/mc@RELEASE.2025-08-13T08-35-41Z
FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates python3-venv tini netcat-openbsd
COPY --from=mc /out/mc /tools/mc
RUN /install.pl curl https://download.bell-sw.com/java/21.0.4+9/bellsoft-jdk21.0.4+9-linux-amd64.tar.gz
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier && chmod +x /tools/coursier
USER c4
ENV PATH=${PATH}:/tools:/tools/jdk/bin
RUN coursier fetch --classpath org.apache.kafka:kafka-clients:3.7.1 > /c4/kafka-clients-classpath
RUN python3 -m venv /c4/venv && /c4/venv/bin/pip install --no-cache-dir boto3==1.38.34
ENTRYPOINT ["tini","--","python3","-u","/app/main.py","serve"]
COPY . /app