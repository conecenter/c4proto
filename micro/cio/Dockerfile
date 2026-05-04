FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN /install.pl useradd 1979
RUN /install.pl apt curl ca-certificates python3 git tini rsync xz-utils rclone netcat-openbsd
RUN /install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
RUN /install.pl curl https://download.bell-sw.com/java/25.0.2+12/bellsoft-jdk25.0.2+12-linux-amd64.tar.gz
RUN /install.pl curl https://dlcdn.apache.org/maven/maven-3/3.9.15/binaries/apache-maven-3.9.15-bin.tar.gz
RUN /install.pl curl https://nodejs.org/dist/v24.15.0/node-v24.15.0-linux-x64.tar.xz
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier && chmod +x /tools/coursier
RUN echo "from cio_client import main;main()" > /ci_serve.py
ENV PATH=${PATH}:/tools:/tools/jdk/bin:/tools/apache/bin:/tools/node/bin
RUN npm install -g allure
USER c4
RUN coursier fetch --classpath org.apache.kafka:kafka-clients:4.2.0 org.slf4j:slf4j-simple:1.7.36 > /tmp/c4classpath && mkdir /c4/c4lib \
 && perl -e 'system "cp", `cat /tmp/c4classpath`=~/[^\s:]+/g, "/c4/c4lib/" and die' && rm /tmp/c4classpath
ENTRYPOINT ["tini", "--", "perl", "-e", "system(@ARGV),sleep 10 while 1", "python3", "-u", "-c", "from cio_server import main;main()"]
COPY . /app
