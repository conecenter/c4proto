FROM ubuntu:18.04
COPY install.pl /
RUN perl install.pl useradd
RUN perl install.pl apt curl unzip \
    lsof telnet mc \
    rsync openssh-client haproxy \
    xserver-xspice openbox firefox spice-vdagent terminology
RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz
RUN perl install.pl curl https://www-eu.apache.org/dist/kafka/2.2.0/kafka_2.12-2.2.0.tgz
RUN perl install.pl curl https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz
RUN perl install.pl curl http://ompc.oss.aliyuncs.com/greys/release/greys-stable-bin.zip
RUN mkdir /c4/db4
ENV JAVA_HOME=/tools/jdk
ENV PATH=${PATH}:/tools/jdk/bin
COPY . /c4
RUN perl /c4/run.pl fix_desktop
RUN chown -R c4:c4 /c4
WORKDIR /c4
USER c4
RUN cd /c4/greys && bash ./install-local.sh
ENTRYPOINT ["perl","run.pl"]
