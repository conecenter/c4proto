#!/usr/bin/perl

use strict;

my $http_port = 8067;
my $sse_port = 8068;
my $zoo_port = 2181;
my $zoo_host = "zookeeper";
my $build_dir = "client/build/test";
my $inbox_prefix = '';
my $kafka_version = "0.10.2.0";
my $kafka = "kafka_2.11-$kafka_version";
my $curl_test = "curl http://127.0.0.1:$http_port/abc";
my $bootstrap_server = "broker:9092";
my $temp = "target";
my $docker_build = "$temp/docker_build";
my $bin = "kafka/bin";
my $user = "c4";
my $registry_prefix = "localhost:5000/";
my $c_script = "inbox_configure.pl";
my $c3script = "c3.pl";

################################################################################

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my $need_dir = sub{ my($d)=@_; -e $d or sy("mkdir -p $d"); $d };
my $sy_in_dir = sub{ my($d,$c)=@_; &$need_dir($d); sy("cd $d && $c") };

my @tasks;

############################### image builds ###################################

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $mkdirs = sub{
    my($ctx_dir,@dirs)=@_;
    mkdir $_ or die $_ for map{"$ctx_dir/$_"} @dirs;
};

my $recycling = sub{
    !-e $_ or rename $_, &$need_dir("$temp/recycle")."/".rand() or die $! for @_;
};

my $run = sub{ "RUN ".join ' && ', @_ };
my $from = sub{
    my($install)=@_;
    (
        "FROM openjdk:8",
        &$run(
            "useradd -mUs /bin/bash $user",
            "apt-get update",
            "apt-get install -y lsof $install",
            "rm -rf /var/lib/apt/lists/*",
        ),
        "COPY . /home/$user",
        &$run("chown -R $user:$user /home/$user"),
    )
};
my $gcp = sub{
    my($from,$ctx_dir,$to)=@_;
    sy("cp -r $from $ctx_dir/$to");
};
my $rename = sub{
    my($dir,$from,$to)=@_;
    rename "$dir/$from"=>"$dir/$to" or die $!;
};

my $app = sub{
    my($image,$cmd,%opt)=@_;
    -e "$docker_build/$image/Dockerfile" or die $image;
    #my $cmd_str = join ",",map{qq("$_")} @$cmd;
    +{image=>"$registry_prefix$image", command => $cmd, restart=>"unless-stopped", %opt}
};

my $app_user = sub{
    my($image,$cmd,%opt)=@_;
    &$app($image, $cmd, user=>$user, working_dir=>"/home/$user", %opt);
};

my $app_staged = sub{
    my($image,$cl,%opt)=@_;
    &$app_user($image, ["app/bin/run",$cl], depends_on => ["broker"], %opt, environment=>{
        C4STATE_TOPIC_PREFIX=>$cl,
        C4BOOTSTRAP_SERVERS=>$bootstrap_server,
        C4INBOX_TOPIC_PREFIX=>$inbox_prefix,
        C4HTTP_PORT=>$http_port,
        C4SSE_PORT=>$sse_port,
        %{$opt{environment}||{}}
    })
};

my $build = sub{
    my($name,$lines) = @_;
    my $ctx_dir = &$need_dir("$docker_build/$name");
    &$put_text("$ctx_dir/Dockerfile",join "\n", &$lines($ctx_dir));
    &$put_text("$ctx_dir/.dockerignore",".dockerignore\nDockerfile")
};

my $build_staged = sub{
    my($name,$f) = @_;
    &$build($name=>sub{
        my($ctx_dir)=@_;
        &$gcp("$name/target/universal/stage"=>$ctx_dir,"app");
        &$rename("$ctx_dir/app/bin",$name=>"run");
        $f && &$f($ctx_dir);
        (&$from(""))
    });
};

my $indent = sub{ join "", map{s/\n/\n  /g; "\n$_"} @_ };
my %yml; my $yml; $yml = sub{ my($arg)=@_; $yml{ref $arg}->($arg) };
$yml{''} = sub{" '$_[0]'"};
$yml{HASH} = sub{ my($h)=@_; &$indent(map{"$_:".&$yml($$h{$_})} sort keys %$h) };
$yml{ARRAY} = sub{ my($l)=@_; &$indent(map{"-".&$yml($_)} @$l) };

my $gen_docker_conf = sub{
    &$recycling($docker_build);
    &$build("c4-zoo"=>sub{
        my($ctx_dir)=@_;
        my $tgz = "tmp/$kafka.tgz";
        &$sy_in_dir("tmp","wget http://www-eu.apache.org/dist/kafka/$kafka_version/$tgz") if !-e $tgz;
        &$sy_in_dir($ctx_dir,"tar -xzf ../../../$tgz");
        &$rename($ctx_dir, $kafka, "kafka");
        &$put_text("$ctx_dir/zookeeper.properties",join "\n",
            "dataDir=db4/zookeeper",
            "clientPort=$zoo_port"
        );
        &$put_text("$ctx_dir/server.properties",join "\n",
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
        &$gcp($c_script=>$ctx_dir,$c_script);
        &$mkdirs($ctx_dir,"db4");
        (&$from(""))
    });
    &$build_staged("c4gate-server");
    &$build_staged("c4gate-publish",sub{
        my($ctx_dir)=@_;
        &$gcp($build_dir=>$ctx_dir,"htdocs");
    });
    #
    &$build_staged("c4gate-sse-example");
    &$build_staged("c4gate-consumer-example");
    #
    &$build("c3-app"=>sub{
        my($ctx_dir)=@_;
        &$gcp($c3script=>$ctx_dir,$c3script);
        &$mkdirs($ctx_dir,"app");
        (&$from("git"))
    });
    &$build("c3-sshd"=>sub{
        my($ctx_dir)=@_;
        &$mkdirs($ctx_dir,"app",".ssh");
        (&$from("git openssh-server"), &$run("mkdir /var/run/sshd"))
    });
    #
    my %base_stack = (
        zookeeper => &$app_user("c4-zoo",["$bin/zookeeper-server-start.sh","zookeeper.properties"]),
        broker => &$app_user("c4-zoo",["$bin/kafka-server-start.sh","server.properties"], depends_on => ["zookeeper"]),
        inbox_configure => &$app_user("c4-zoo",["perl",$c_script], depends_on => ["broker"]),
        gate => &$app_staged("c4gate-server","ee.cone.c4gate.HttpGatewayApp"),
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
        publish => &$app_staged("c4gate-publish", "ee.cone.c4gate.PublishApp",
            restart => "on-failure",
            environment => {
                C4PUBLISH_DIR=>"htdocs", C4PUBLISH_THEN_EXIT=>"1"
            },
        )
    );
    my $stacks = {
        "c3" => {%base_ui_stack,map{(
            "c3_$_\_app" => &$app_user("c3-app",["perl",$c3script], depends_on => ["broker"]),
            "c3_$_\_sshd" => &$app("c3-sshd",["/usr/sbin/sshd", "-D"]),
        )} qw[main exch]},
        (map{($_ => {%base_stack,
            app=>&$app_staged("c4gate-consumer-example",$_)
        })}
            "ee.cone.c4gate.TestConsumerApp",
            "ee.cone.c4gate.TestSerialApp",
            "ee.cone.c4gate.TestParallelApp",
        ),
        (map{($$_[0] => {%base_ui_stack,
            map{($$_[0]=>&$app_staged("c4gate-sse-example",@$_))} @{$$_[1]||die}
        })}
            ["test_sse"=>[["ee.cone.c4gate.TestSSEApp"]]], # http://localhost:8067/sse.html#
            ["test_ui"=>[
                ["ee.cone.c4gate.TestTodoApp"],
                ["ee.cone.c4gate.TestCoWorkApp"],
                ["ee.cone.c4gate.TestCanvasApp", environment=>{C4PUBLISH_DIR=>"htdocs"}],
                ["ee.cone.c4gate.TestPasswordApp"],
            ]]
        ),
        "override" => \%test_stack
    };
    #
    for my $name(sort keys %$stacks){
        &$put_text("$docker_build/docker.$name.yml","#### this file is generated ####\n".&$yml({
            version => "3", services => $$stacks{$name}
        }))
    }
};

my $gen_docker_images = sub{
    for my $ctx_dir(sort grep{-e "$_/Dockerfile"} <$docker_build/*>){
        my $name = substr $ctx_dir, 1+length $docker_build;
        sy("docker build -t $registry_prefix$name $ctx_dir")
    }
};

my $webpack = sub{
    &$sy_in_dir("client","./node_modules/webpack/bin/webpack.js");# -d
};

push @tasks, ["### build ###"];
push @tasks, ["build_all", sub{
    my($mode) = @_;
    sy("sbt clean stage");
    &$sy_in_dir("client","npm install");
    &$recycling($build_dir);
    &$webpack();
    &$gen_docker_conf();
    &$gen_docker_images();
}];
push @tasks, ["build_some_server", sub{
    my($mode) = @_;
    sy("sbt stage");
    &$gen_docker_conf();
    &$gen_docker_images();
}];
push @tasks, ["build_some_client", sub{
    my($mode) = @_;
    &$webpack();
    &$gen_docker_conf();
    &$gen_docker_images();
}];
push @tasks, ["build_conf_only", sub{
    my($mode) = @_;
    &$gen_docker_conf();
}];

################################################################################

push @tasks, ["### debug ###"];
push @tasks, ["inbox_log_tail", sub{
    sy("$bin/kafka-console-consumer.sh --bootstrap-server $bootstrap_server --topic $inbox_prefix.inbox.log")
}];
push @tasks, ["inbox_test", sub{
    sy("$bin/kafka-verifiable-consumer.sh --broker-list $bootstrap_server --topic $inbox_prefix.inbox --group-id dummy-".rand())
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
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{$$_[0]=~/^\./ ? () : $$_[0]=~"^#" ? $$_[0] : "  $0 $$_[0]"} @tasks;
}
