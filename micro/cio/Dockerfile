FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN /install.pl useradd 1979
RUN /install.pl apt curl ca-certificates python3 git tini rsync
# rsync for steps
RUN /install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
RUN /install.pl curl https://download.bell-sw.com/java/21.0.4+9/bellsoft-jdk21.0.4+9-linux-amd64.tar.gz
# for tests
#RUN /install.pl curl https://dlcdn.apache.org/maven/maven-3/3.9.7/binaries/apache-maven-3.9.7-bin.tar.gz
RUN /install.pl curl https://dlcdn.apache.org/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz
RUN /install.pl curl https://github.com/coursier/launchers/raw/master/coursier && chmod +x /tools/coursier
RUN echo "from cio_client import main;main()" > /ci_serve.py
USER c4
ENV PATH=${PATH}:/tools:/tools/jdk/bin:/tools/apache/bin
# /tools/apache/bin for maven
RUN coursier fetch --classpath org.apache.kafka:kafka-clients:3.7.1 > /tmp/c4classpath && mkdir /c4/c4lib \
 && perl -e 'system "cp", `cat /tmp/c4classpath`=~/[^\s:]+/g, "/c4/c4lib/" and die' && rm /tmp/c4classpath
ENTRYPOINT ["tini", "--", "perl", "-e", "system(@ARGV),sleep 10 while 1", "python3", "-u", "-c", "from cio_server import main;main()"]
