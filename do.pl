#!/usr/bin/perl

use strict;

my $port_prefix = $ENV{C4PORT_PREFIX} || 8000;
my $http_port = $port_prefix+67;
my $sse_port = $port_prefix+68;
my $zoo_port = $port_prefix+81;
my $zoo_host = $ENV{C4ZOOKEEPER_HOST} || "127.0.0.1";
my $kafka_host = $ENV{C4KAFKA_HOST} || "127.0.0.1";
my $kafka_port = $port_prefix+92;
my $build_dir = "./client/build/test";
my $inbox_prefix = '';
my $kafka_version = "0.10.2.0";
my $kafka = "kafka_2.11-$kafka_version";
my $curl_test = "curl http://127.0.0.1:$http_port/abc";
my $bootstrap_server = "$kafka_host:$kafka_port";

sub syn{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $in_dir = sub{
    my($dir_name,$process)=@_;
    return sub{
        (-e $_ or mkdir $_) and chdir $_ or die for $dir_name;
        &$process();
        chdir $_ or die for "..";
    };
};

my $in_tmp_dir = sub{
    my($process)=@_;
    &$in_dir("tmp",$process);
};

my @tasks;

################################################################################

my $need_downloaded_kafka = sub{
    my $tgz = "$kafka.tgz";
    sy("wget http://www-eu.apache.org/dist/kafka/$kafka_version/$tgz") if !-e $tgz;
    $tgz;
};

my $tar_x = "tar -xzf ";

my $need_extracted_kafka = sub{
    -e $kafka or sy($tar_x.&$need_downloaded_kafka());
    "$kafka/bin"
};

my $inbox_configure = sub{
    my $bin = &$need_extracted_kafka();
    my $kafka_topics = "$bin/kafka-topics.sh --zookeeper $zoo_host:$zoo_port --topic .inbox";
    sy("$kafka_topics --create --partitions 1 --replication-factor 1 --force")
        if 0 > index syf("$kafka_topics --list"), ".inbox";
    my $kafka_configs = "$bin/kafka-configs.sh --zookeeper $zoo_host:$zoo_port --entity-type topics ";
    my $infinite_lag = "min.compaction.lag.ms=9223372036854775807";
    my $compression = "compression.type=producer";
    sy("$kafka_configs --alter --entity-name .inbox --add-config $infinite_lag,$compression");
    die if 0 > index syf("$kafka_configs --describe --entity-name .inbox"),$infinite_lag;
};

my $stop_kafka = sub{
    my $bin = &$need_extracted_kafka();
    syn("$bin/kafka-server-stop.sh");
    syn("$bin/zookeeper-server-stop.sh");
};

my $start_zookeeper = sub{
    my($args)=@_;
    my $bin = &$need_extracted_kafka();
    &$put_text("zookeeper.properties",join "\n",
        "dataDir=db4/zookeeper",
        "clientPort=$zoo_port"
    );
    "$bin/zookeeper-server-start.sh $args zookeeper.properties";
};

my $start_kafka = sub{
    my($args)=@_;
    my $bin = &$need_extracted_kafka();
    &$put_text("server.properties",join "\n",
        "listeners=PLAINTEXT://$bootstrap_server",
        "log.dirs=db4/kafka-logs",
        "zookeeper.connect=$zoo_host:$zoo_port",
        "log.cleanup.policy=compact",
        "log.segment.bytes=104857600", #active segment is not compacting, so we reduce it
        "log.cleaner.delete.retention.ms=3600000", #1h
        "log.roll.hours=1", #delete-s will be triggered to remove?
        "compression.type=uncompressed", #probably better compaction for .state topics
        "message.max.bytes=3000000" #seems to be compressed
    );
    "$bin/kafka-server-start.sh $args server.properties";
};

push @tasks, [".run.c3", &$in_dir("app",sub{
    if(!-e ".git"){
        sy("git init");
        sy("git config receive.denyCurrentBranch ignore");
    }
    while(sleep 1){
        my $script = "start.run";
        -e $script or sy("git reset --hard");
        -e $script and exec "sh $script" ;
    }
})];
push @tasks, [".run.inbox-configure", sub{ &$inbox_configure(); sleep 3600 }];
push @tasks, [".run.zookeeper", sub{ exec &$start_zookeeper("") or die }];
push @tasks, [".run.kafka", sub{ exec &$start_kafka("") or die }];
push @tasks, ["restart_kafka", &$in_tmp_dir(sub{
    &$stop_kafka();
    sy(&$start_zookeeper("-daemon"));
    sleep 5;
    sy(&$start_kafka("-daemon"));
    sy("jps");
})];
push @tasks, ["stop_kafka", &$in_tmp_dir(sub{&$stop_kafka()})];

push @tasks, ["inbox_log_tail", &$in_tmp_dir(sub{
    my $bin = &$need_extracted_kafka();
    sy("$bin/kafka-console-consumer.sh --bootstrap-server $bootstrap_server --topic $inbox_prefix.inbox.log")
})];
push @tasks, ["inbox_test", &$in_tmp_dir(sub{
    my $bin = &$need_extracted_kafka();
    sy("$bin/kafka-verifiable-consumer.sh --broker-list $bootstrap_server --topic $inbox_prefix.inbox --group-id dummy-".rand())
})];

my $client = sub{
    my($inst)=@_;
    unlink or die $! for <$build_dir/*>;
    &$in_dir("client",sub{
        sy("npm install") if $inst;
        sy("./node_modules/webpack/bin/webpack.js");# -d
    })->();
    $build_dir
};

push @tasks, ["stage", sub{
    sy("sbt clean docker:stage");
    &$client(1);
}];

my $env = "C4BOOTSTRAP_SERVERS=$bootstrap_server C4INBOX_TOPIC_PREFIX='$inbox_prefix' C4HTTP_PORT=$http_port C4SSE_PORT=$sse_port ";
sub staged{
    $ENV{C4NOSTAGE}?
        "C4STATE_TOPIC_PREFIX=$_[1] sbt '$_[0]/run $_[1]'":
        "C4STATE_TOPIC_PREFIX=$_[1] $_[0]/target/universal/stage/bin/$_[0] $_[1]"
}
push @tasks, ["gate_publish", sub{
    my $build_dir = &$client(0);
    sy("$env C4PUBLISH_DIR=$build_dir C4PUBLISH_THEN_EXIT=1 ".staged("c4gate-publish","ee.cone.c4gate.PublishApp"))
}];
push @tasks, ["gate_server_run", sub{
    &$in_tmp_dir($inbox_configure)->();
    sy("$env C4STATE_REFRESH_SECONDS=100 ".staged("c4gate-server","ee.cone.c4gate.HttpGatewayApp"));
}];


push @tasks, [".run.staged", sub{
    exec "$env app/bin/run $ENV{C4STATE_TOPIC_PREFIX}" or die
}];

push @tasks, ["build_docker_images", &$in_tmp_dir(sub{
    my $tgz = &$need_downloaded_kafka();
    my $user = "c4";
    my $run = sub{ "RUN ".join ' && ', @_ };
    my $from = sub{(
        "FROM openjdk:8",
        &$run(
            "useradd -mUs /bin/bash $user",
            "apt-get update",
            "apt-get install -y lsof $_[0]",
            "rm -rf /var/lib/apt/lists/*"
        )
    )};
    my $volume = sub{
        my $dirs = join " ", map{"/home/$user/$_"} @_;
        (&$run("mkdir $dirs", "chown $user:$user $dirs"), "VOLUME $dirs")
    };
    my $cp = sub{
        my($f,$t)=@_;
        sub{ sy("cp -r $f $t"); "COPY $t /home/$user/$t" }
    };
    my $mod_x = sub{ &$run("chmod +x /home/$user/$_[0]") };
    my $user_mode = [
        &$cp("../../do.pl"=>"do.pl"),
        &$mod_x("do.pl"),
        "USER $user",
        "WORKDIR /home/$user",
    ];

    my $build = sub{
        my($name,@lines) = @_;
        rename "app.$name", "recycled.".rand() or die if -e "app.$name";
        &$in_dir("app.$name",sub{
            &$put_text("Dockerfile",join "\n", map{(ref $_)?&$_():$_} @lines);
            sy("docker build -t localhost:5000/$name .");
        })->();
    };
    my $build_zoo = sub{
        my($name) = @_;
        &$build("c4-$name",
            &$from(""),
            &$volume("db4"),
            &$cp("../$tgz",$tgz),
            @$user_mode,
            &$run("$tar_x $tgz"),
            qq{CMD ["./do.pl",".run.$name"]}
        )
    };
    my $build_staged = sub{
        my($name) = @_;
        &$build($name,
            &$from(""),
            &$cp("../../$name/target/universal/stage"=>"app"),
            sub{ rename "app/bin/$name"=>"app/bin/run" or die; () },
            &$mod_x("app/bin/run"),
            @$user_mode,
            qq{CMD ["./do.pl",".run.staged"]}
        );
    };

    &$build_zoo("zookeeper");
    &$build_zoo("kafka");
    &$build_zoo("inbox-configure");
    &$build_staged("c4gate-server");

    &$build("c3-app",
        &$from("git"),
        &$volume("app"),
        @$user_mode,
        qq{CMD ["./do.pl",".run.c3"]}
    );
    &$build("c3-sshd",
        &$from("git openssh-server"),
        &$volume("app",".ssh"),
        &$run("mkdir /var/run/sshd"),
        qq{CMD ["/usr/sbin/sshd", "-D"]}
    );
})];


################################################################################

push @tasks, ["test_es_examples", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
}];
push @tasks, ["test_not_effective_join_bench", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.NotEffectiveAssemblerTest' ");
}];
push @tasks, ["test_post_get_tcp_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestConsumerApp"))
}];
push @tasks, ["test_post_get_check", sub{
    my $v = int(rand()*10);
    sy("$curl_test -X POST -d $v");
    sleep 1;
    sy("$curl_test -v");
    sleep 4;
    sy("$curl_test -v");
    print " -- should be posted * 3\n";
}];

push @tasks, ["test_actor_serial_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestSerialApp"))
}];
push @tasks, ["test_actor_parallel_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestParallelApp"))
}];
push @tasks, ["test_actor_check", sub{
    sy("$curl_test -X POST") for 0..11;
}];
push @tasks, ["test_big_message_check", &$in_tmp_dir(sub{
    sy("dd if=/dev/zero of=test.bin bs=1M count=4 && $curl_test -v -XPOST -T test.bin")
})];


push @tasks, ["test_ui_timer_service_run", sub{ # http://localhost:8067/sse.html#
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestSSEApp"))
}];
push @tasks, ["test_ui_todo_service_run", sub{
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestTodoApp"))
}];
push @tasks, ["test_ui_cowork_service_run", sub{
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestCoWorkApp"))
}];
push @tasks, ["test_ui_canvas_service_run", sub{
    sy("$env C4PUBLISH_DIR=$build_dir C4PUBLISH_THEN_EXIT='' ".staged("c4gate-sse-example","ee.cone.c4gate.TestCanvasApp"))
}];
push @tasks, ["test_ui_password_service_run", sub{
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestPasswordApp"))
}];

################################################################################

if($ARGV[0]) {
    $ARGV[0] eq $$_[0] and $$_[1]->() for @tasks;
} else {
    print "usage:\n";
    $$_[0]=~/^\./ or print "\t$0 $$_[0]\n" for @tasks;
}
