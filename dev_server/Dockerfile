FROM ghcr.io/conecenter/c4replink:v2
USER root
RUN perl install.pl apt \
  curl software-properties-common '# add-apt-repository, ca-certificates for curl' xz-utils '#installs' \
  libyaml-libyaml-perl libjson-xs-perl python \
  supervisor haproxy \
  uuid-runtime '#kafka cert gen' \
  mc lsof '#debug'
RUN /install.pl curl https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.5%2B10/OpenJDK11U-jdk_x64_linux_hotspot_11.0.5_10.tar.gz
RUN /install.pl curl https://git.io/coursier-cli-linux && chmod +x /tools/coursier
RUN /install.pl curl https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz
RUN /install.pl curl https://archive.apache.org/dist/kafka/2.2.0/kafka_2.12-2.2.0.tgz
RUN ln -s /c4/supervisord.conf /etc/supervisord.conf \
 && mkdir /c4repo && chown c4:c4 /c4repo
USER c4
ENV PATH=${PATH}:/tools/jdk/bin:/tools:/tools/node/bin:/tools/kafka/bin:/c4/.bloop
RUN curl -L https://github.com/scalacenter/bloop/releases/download/v1.3.4/install.py | python
COPY init.pl /
ENTRYPOINT ["perl","/init.pl"]
