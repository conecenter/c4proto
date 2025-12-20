FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN /install.pl useradd 1979
RUN /install.pl apt curl ca-certificates unzip tini
RUN /install.pl curl https://download.bell-sw.com/java/25.0.1+13/bellsoft-jdk25.0.1+13-linux-amd64.tar.gz
RUN /install.pl curl https://dl.k8s.io/release/v1.33.1/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
RUN curl -L https://github.com/com-lihaoyi/Ammonite/releases/download/3.0.4/3.3-3.0.4 -o /tmp/amm \
 && echo "#!/usr/bin/env sh" > /tools/amm && cat /tmp/amm >> /tools/amm && chmod +x /tools/amm && chown c4:c4  /tools/amm \
 && rm /tmp/amm
USER c4
RUN curl -L https://github.com/async-profiler/async-profiler/releases/download/v4.1/async-profiler-4.1-linux-x64.tar.gz -o /c4/profiler.tar.gz
RUN curl -L https://github.com/alibaba/arthas/releases/download/arthas-all-4.1.1/arthas-bin.zip -o /c4/arthas-bin.zip
RUN mkdir -p /tmp/c4dig/arthas \
 && cd /tmp/c4dig && tar xzf /c4/profiler.tar.gz && mv async-profiler-4.1-linux-x64 async \
 && mkdir async_out \
 && cd /tmp/c4dig/arthas && unzip /c4/arthas-bin.zip \
 && cd /tmp && tar -C /tmp -czf /c4/c4dig.tar.gz c4dig \
 && rm -r /tmp/c4dig
ENV PATH=${PATH}:/tools/:/tools/jdk/bin
ENTRYPOINT ["tini","--","perl","-e","-e and exec 'amm', $_ for map{$_.'/app.scala'} split ':', $ENV{C4UP_PATH}; die"]
#todo: using compilation image can save some memory and boot time
COPY . /app