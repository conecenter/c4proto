#!/usr/bin/perl

use strict;

my $http_port = 8067;
my $sse_port = 8068;
my $zoo_port = 2181;
my $zoo_host = $ENV{C4ZOOKEEPER_HOST} || "zookeeper";
my $build_dir = "client/build/test";
my $inbox_prefix = '';
my $kafka_version = "0.10.2.0";
my $kafka = "kafka_2.11-$kafka_version";
my $curl_test = "curl http://127.0.0.1:$http_port/abc";
my $bootstrap_server = ($ENV{C4KAFKA_HOST} || "broker").":9092";
my $temp = "tmp";
my $docker_build = "$temp/docker_build";
my $bin = "kafka/bin";

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }

my $need_dir = sub{ my($d)=@_; -e $d or sy("mkdir -p $d"); $d };
my $sy_in_dir = sub{ my($d,$c)=@_; &$need_dir($d); sy("cd $d && $c") };

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $recycling = sub{
    !-e $_ or rename $_, &$need_dir("$temp/recycle")."/".rand() or die $! for @_;
};

my @tasks;

################################################################################

my $inbox_configure = sub{
    my $kafka_topics = "$bin/kafka-topics.sh --zookeeper $zoo_host:$zoo_port --topic .inbox";
    sy("$kafka_topics --create --partitions 1 --replication-factor 1 --force")
        if 0 > index syf("$kafka_topics --list"), ".inbox";
    my $kafka_configs = "$bin/kafka-configs.sh --zookeeper $zoo_host:$zoo_port --entity-type topics ";
    my $infinite_lag = "min.compaction.lag.ms=9223372036854775807";
    my $compression = "compression.type=producer";
    sy("$kafka_configs --alter --entity-name .inbox --add-config $infinite_lag,$compression");
    die if 0 > index syf("$kafka_configs --describe --entity-name .inbox"),$infinite_lag;
};

push @tasks, [".run.zookeeper", sub{
    &$put_text("zookeeper.properties",join "\n",
        "dataDir=db4/zookeeper",
        "clientPort=$zoo_port"
    );
    exec "$bin/zookeeper-server-start.sh zookeeper.properties" or die;
}];

push @tasks, [".run.kafka", sub{
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
    exec "$bin/kafka-server-start.sh server.properties" or die;
}];

push @tasks, [".run.c3", sub{
    chdir "app" or die;
    if(!-e ".git"){
        sy("git init");
        sy("git config receive.denyCurrentBranch ignore");
    }
    while(sleep 1){
        my $script = "start.run";
        -e $script or sy("git reset --hard");
        -e $script and exec "sh $script" ;
    }
}];
push @tasks, [".run.inbox-configure", sub{ &$inbox_configure(); sleep 3600 }];
push @tasks, ["inbox_log_tail", sub{
    sy("$bin/kafka-console-consumer.sh --bootstrap-server $bootstrap_server --topic $inbox_prefix.inbox.log")
}];
push @tasks, ["inbox_test", sub{
    sy("$bin/kafka-verifiable-consumer.sh --broker-list $bootstrap_server --topic $inbox_prefix.inbox --group-id dummy-".rand())
}];

my $client = sub{
    my($inst)=@_;
    &$recycling($build_dir);
    &$sy_in_dir("client","npm install") if $inst;
    &$sy_in_dir("client","./node_modules/webpack/bin/webpack.js");# -d
};

push @tasks, ["stage", sub{
    sy("sbt clean stage");
    &$client(1);
}];

#... gate inbox conf?
#... need publish container

push @tasks, [".run.staged", sub{
    my $env = "C4BOOTSTRAP_SERVERS=$bootstrap_server C4INBOX_TOPIC_PREFIX='$inbox_prefix' C4HTTP_PORT=$http_port C4SSE_PORT=$sse_port ";
    exec "$env app/bin/run $ENV{C4STATE_TOPIC_PREFIX}" or die
}];

##### image builds

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
my $copy = sub{ my($t)=@_; "COPY $t /home/$user/$t" };
my $gcp = sub{
    my($from,$ctx_dir,$to)=@_;
    sy("cp -r $from $ctx_dir/$to");
    &$copy($to);
};
my $rename = sub{
    my($dir,$from,$to)=@_;
    rename "$dir/$from"=>"$dir/$to" or die $!;
};

my $mod_x = sub{ "chmod +x /home/$user/$_[0]" };

my $user_mode = sub{
    my($ctx_dir,$name) = @_;
    my $script = "app.pl";
    (
        &$gcp($script=>$ctx_dir,$script),
        &$run(&$mod_x($script),"chown -R $user:$user /home/$user"),
        "USER $user",
        "WORKDIR /home/$user",
        qq{CMD ["./$script",".run.$name"]}
    )
};

my $build = sub{
    my($name,$lines,$service_args) = @_;
    my $ctx_dir = &$need_dir("$docker_build/$name");
    &$put_text("$ctx_dir/Dockerfile",join "\n", &$lines($ctx_dir));
    my $image = "localhost:5000/$name";
    sy("docker build -t $image $ctx_dir");
    ($name=>{restart=>"unless-stopped", image=>$image, %$service_args})
};

my $build_zoo = sub{
    my($name,$depends_on) = @_;
    &$build("c4-$name"=>sub{
        my($ctx_dir)=@_;
        my $tgz = "$temp/$kafka.tgz";
        &$sy_in_dir($temp,"wget http://www-eu.apache.org/dist/kafka/$kafka_version/$tgz") if !-e $tgz;
        &$sy_in_dir($ctx_dir,"tar -xzf ../../$kafka.tgz");
        &$rename($ctx_dir, $kafka, "kafka");
        (
            &$from(""),
            &$volume("db4"),
            &$copy("kafka"),
            &$user_mode($ctx_dir,$name)
        )
    },{
        @$depends_on ? (depends_on => $depends_on) : ()
    })
};
my $build_staged = sub{
    my($name,$lines,$service_args) = @_;
    &$build($name=>sub{
        my($ctx_dir)=@_;
        my $cp_app = &$gcp("$name/target/universal/stage"=>$ctx_dir,"app");
        &$rename("$ctx_dir/app/bin",$name=>"run");
        (
            &$from(""),
            $cp_app,
            &$run(&$mod_x("app/bin/run")),
            $lines ? &$lines($ctx_dir) : (),
            &$user_mode($ctx_dir,"staged")
        )
    },{
        depends_on => ["broker"],
        %{$service_args||{}}
    });
};

my $indent = sub{ join "", map{s/\n/\n  /g; "\n$_"} @_ };
my %yml; my $yml; $yml = sub{ my($arg)=@_; $yml{ref $arg}->($arg) };
$yml{''} = sub{" '$_[0]'"};
$yml{HASH} = sub{ my($h)=@_; &$indent(map{"$_:".&$yml($$h{$_})} sort keys %$h) };
$yml{ARRAY} = sub{ my($l)=@_; &$indent(map{"-".&$yml($_)} @$l) };

push @tasks, ["build_docker_images", sub{
    #sy("sbt stage");
    &$recycling($docker_build);

    my %img = (
        &$build_zoo("zookeeper",[]),
        &$build_zoo("kafka",["zookeeper"]),
        &$build_zoo("inbox-configure",["broker"]),
        &$build_staged("c4gate-server"),
        &$build_staged("c4gate-publish",sub{
            my($ctx_dir)=@_;
            (
                &$gcp($build_dir=>$ctx_dir,"htdocs"),
                qq{ENV C4PUBLISH_DIR="htdocs" C4PUBLISH_THEN_EXIT="1"}
            )
        },{
            restart => "on-failure",
        }),
        #
        &$build_staged("c4gate-sse-example"),
        &$build_staged("c4gate-consumer-example"),
        #
        &$build("c3-app"=>sub{
            my($ctx_dir)=@_;
            (
                &$from("git"),
                &$volume("app"),
                &$user_mode($ctx_dir,"c3")
            )
        },{
            depends_on => ["broker"],
        }),
        &$build("c3-sshd"=>sub{(
            &$from("git openssh-server"),
            &$volume("app",".ssh"),
            &$run("mkdir /var/run/sshd"),
            qq{CMD ["/usr/sbin/sshd", "-D"]}
        )},{}),
    );
    my $app = sub{
        my($image,$name,$env)=@_;
        +{
            %{$img{$image}||die $image},
            $name ? (environment=>{ C4STATE_TOPIC_PREFIX=>$name, %{$env||{}} }) : ()
        }
    };
    my %base_stack = (
        zookeeper => &$app("c4-zookeeper"),
        broker => &$app("c4-kafka"),
        inbox_configure => &$app("c4-inbox-configure"),
        gate => &$app("c4gate-server","ee.cone.c4gate.HttpGatewayApp"),
    );
    my %test_stack = (
        gate => {
            environment=>{
                C4STATE_REFRESH_SECONDS => 100
            },
            ports => ["$http_port:$http_port","$sse_port:$sse_port"]
        }
    );

    my %base_ui_stack = (%base_stack,
        publish => &$app("c4gate-publish","ee.cone.c4gate.PublishApp")
    ); #... sleep
    my $stacks = {
        "c3" => {%base_ui_stack,
            c3_main_app => &$app("c3-app"), c3_main_sshd => &$app("c3-sshd"),
            c3_exch_app => &$app("c3-app"), c3_exch_sshd => &$app("c3-sshd"),
        },
        (map{($_ => {%base_stack,
            app=>&$app("c4gate-consumer-example",$_)
        })}
            "ee.cone.c4gate.TestConsumerApp",
            "ee.cone.c4gate.TestSerialApp",
            "ee.cone.c4gate.TestParallelApp",
        ),
        (map{($$_[0] => {%base_ui_stack,
            map{($$_[0]=>&$app("c4gate-sse-example",@$_))} @{$$_[1]||die}
        })}
            ["test_sse"=>[["ee.cone.c4gate.TestSSEApp"]]], # http://localhost:8067/sse.html#
            ["test_ui"=>[
                ["ee.cone.c4gate.TestTodoApp"],
                ["ee.cone.c4gate.TestCoWorkApp"],
                ["ee.cone.c4gate.TestCanvasApp",{C4PUBLISH_DIR=>"htdocs"}],
                ["ee.cone.c4gate.TestPasswordApp"],
            ]]
        ),
        "override" => \%test_stack
    };
    for my $name(sort keys %$stacks){
        &$put_text("$docker_build/docker.$name.yml",&$yml({
            version => "3", services => $$stacks{$name}
        }))
    }
}];



################################################################################

my $staged_up = sub{
    my($name)=@_;
    sy("docker-compose -f $docker_build/docker.$name.yml -f $docker_build/docker.override.yml up -d --remove-orphans")
};

push @tasks, ["### tests ###"];
push @tasks, ["test_es_examples", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
}];
push @tasks, ["test_not_effective_join_bench", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.NotEffectiveAssemblerTest' ");
}];


push @tasks, ["test_post_get_tcp_service_run", sub{
    &$staged_up("ee.cone.c4gate.TestConsumerApp")
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

push @tasks, ["test_actor_serial_up", sub{
    &$staged_up("ee.cone.c4gate.TestSerialApp")
}];
push @tasks, ["test_actor_parallel_up", sub{
    &$staged_up("ee.cone.c4gate.TestParallelApp")
}];
push @tasks, ["test_actor_check", sub{
    sy("$curl_test -X POST") for 0..11;
}];
push @tasks, ["test_big_message_check", sub{
    &$sy_in_dir($temp,"dd if=/dev/zero of=test.bin bs=1M count=4 && $curl_test -v -XPOST -T test.bin")
}];
push @tasks, ["test_sse_up", sub{ &$staged_up("test_sse") }];
push @tasks, ["test_ui_up", sub{ &$staged_up("test_ui") }];


################################################################################

if($ARGV[0]) {
    $ARGV[0] eq $$_[0] and $$_[1]->() for @tasks;
} else {
    print "usage:\n";
    $$_[0]=~/^\./ or print "\t$0 $$_[0]\n" for @tasks;
}
