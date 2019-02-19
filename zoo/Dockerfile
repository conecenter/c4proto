FROM ubuntu:18.04
COPY install.pl /
RUN useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4
RUN perl install.pl apt curl unzip
RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz
RUN perl install.pl curl http://www-eu.apache.org/dist/kafka/2.0.0/kafka_2.11-2.0.0.tgz
RUN perl install.pl curl https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz
RUN perl install.pl curl https://github.com/visualvm/visualvm.src/releases/download/1.4.2/visualvm_142.zip
RUN perl install.pl apt lsof rsync telnet mc xserver-xspice openbox firefox spice-vdagent terminology
RUN mkdir /c4/db4
ENV JAVA_HOME=/c4/jdk
ENV PATH=${PATH}:/c4/jdk/bin:/c4/visualvm/bin
COPY . /c4
RUN echo "allowed_users=anybody" > /etc/X11/Xwrapper.config \
 && cp /etc/X11/spiceqxl.xorg.conf /etc/X11/c4spiceqxl.xorg.conf \
 && chown c4:c4 /etc/X11/c4spiceqxl.xorg.conf \
 && perl  -i -pe 's{(/python\n)}{$1\ntemp_dir=None\n}' /usr/bin/Xspice \
 && mkdir -p /c4/.config/autostart
RUN chown -R c4:c4 /c4
WORKDIR /c4
USER c4
ENTRYPOINT ["perl","run.pl"]
