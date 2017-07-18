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
my $uid = 1979;
my $registry_prefix = "localhost:5000/c4";
my $c_script = "inbox_configure.pl";
my $project = $ENV{USER} || die;

################################################################################


sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

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
            "useradd --base-dir / --create-home --user-group --uid $uid --shell /bin/bash $user",
            "apt-get update",
            "apt-get install -y lsof $install",
            "rm -rf /var/lib/apt/lists/*",
        ),
        "COPY . /$user",
        &$run("chown -R $user:$user /$user"),
    )
};
my $gcp = sub{
    my($from,$ctx_dir,$to)=@_;
    sy("cp -r $from $ctx_dir/$to");
};
my $rename = sub{
    my($dir,$from,$to)=@_;
    rename "$dir/$from"=>"$dir/$to" or die "$dir,$from,$to,$!";
};

my $app = sub{ my %opt = @_; +{restart=>"unless-stopped", %opt} };

my $extract_env = sub{
    my %opt = @_;
    my %env = map{/^C4/?($_=>$opt{$_}):()} keys %opt;
    (
        (%env ? (environment => \%env):()),
        (map{/^C4/?():($_=>$opt{$_})} keys %opt)
    )
};

my $app_user = sub{
    my %opt = @_;
    &$app(&$extract_env(user=>$user, working_dir=>"/$user", %opt));
};

my $client_options = sub{(
    depends_on => ["broker"],
    C4BOOTSTRAP_SERVERS => $bootstrap_server,
    C4INBOX_TOPIC_PREFIX => $inbox_prefix,
    C4STATE_TOPIC_PREFIX => $_[0],
)};

my $app_staged = sub{
    my($image,$cl,%opt)=@_;
    &$app_user(
        image => "$registry_prefix-$image",
        command => ["app/bin/run",$cl],
        &$client_options($cl),
        %opt,
    )
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
        &$gcp("c4$name/target/universal/stage"=>$ctx_dir,"app");
        &$rename("$ctx_dir/app/bin","c4$name"=>"run");
        $f && &$f($ctx_dir);
        (&$from(""))
    });
};

my $volumes = sub{(volumes => [map{"vol-$user-$_:/$user/$_"}@_])};

my $indent = sub{ join "", map{s/\n/\n  /g; "\n$_"} @_ };
my %yml; my $yml; $yml = sub{ my($arg)=@_; $yml{ref $arg}->($arg) };
$yml{''} = sub{" '$_[0]'"};
$yml{HASH} = sub{ my($h)=@_; &$indent(map{"$_:".&$yml($$h{$_})} sort keys %$h) };
$yml{ARRAY} = sub{ my($l)=@_; &$indent(map{"-".&$yml($_)} @$l) };
my $put_yml = sub{
    my($path,$data)=@_;
    &$put_text($path,"#### this file is generated ####\n".&$yml({
        version => "3", %$data
    }))
};

my $gen_docker_conf = sub{
    &$recycling($docker_build);
    &$build("zoo"=>sub{
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
    &$build_staged("gate-server");
    &$build_staged("gate-publish",sub{
        my($ctx_dir)=@_;
        &$gcp($build_dir=>$ctx_dir,"htdocs");
    });
    #
    &$build_staged("gate-sse-example",sub{
        my($ctx_dir)=@_;
        &$mkdirs($ctx_dir,"htdocs");
    });
    &$build_staged("gate-consumer-example");
    #
    &$build("sshd"=>sub{
        my($ctx_dir)=@_;
        &$mkdirs($ctx_dir,"db4");
        (&$from("openssh-server"), &$run("mkdir /var/run/sshd"))
    });
    #
    my %base_stack = (
        zookeeper => &$app_user(
            image => "$registry_prefix-zoo",
            command => ["$bin/zookeeper-server-start.sh","zookeeper.properties"],
            &$volumes("db4"),
        ),
        broker => &$app_user(
            image => "$registry_prefix-zoo",
            command => ["$bin/kafka-server-start.sh","server.properties"],
            depends_on => ["zookeeper"],
            &$volumes("db4"),
        ),
        inbox_configure => &$app_user(
            image => "$registry_prefix-zoo",
            command => ["perl",$c_script],
            depends_on => ["broker"],
        ),
        gate => &$app_staged("gate-server","ee.cone.c4gate.HttpGatewayApp",
            C4HTTP_PORT=>$http_port,
            C4SSE_PORT=>$sse_port,
        ),
        sshd => &$app(
            image => "$registry_prefix-sshd",
            command => ["/usr/sbin/sshd", "-D"],
            &$volumes("db4")
        ),
    );

    my %base_ui_stack = (%base_stack,
        publish => &$app_staged("gate-publish", "ee.cone.c4gate.PublishApp",
            restart => "on-failure",
        )
    );
    #
    my $app_conf_dir = "tmp/app_conf";
    -e $app_conf_dir or print "consider adding $app_conf_dir\n";
    for my $override_file(<$app_conf_dir/*>){
        my $project = $override_file=~m{/(\w+)\.override\.yml$} ? $1 : next;
        &$build("compose-$project",sub{
            my($ctx_dir)=@_;
            sy("cp $override_file $ctx_dir/docker-compose.override.yml");
            my $services = {
                %base_ui_stack,
                map{("app_$_" => &$app_user(
                    image => "$registry_prefix-$project-$_",
                    &$client_options("$project-$_"),
                ))} map{/^\s+app_([a-z]+):\s+$/?"$1":()} `cat $override_file`
            };
            &$put_yml("$ctx_dir/docker-compose.yml",{ services => $services });
            (
                "FROM docker/compose:1.14.0",
                "COPY . /"
            )
        })
    }
    ####
    &$put_yml("$docker_build/override.yml",{
        services => {
            gate => {
                environment=>{
                    C4STATE_REFRESH_SECONDS => 100
                },
                ports => ["$http_port:$http_port","$sse_port:$sse_port"]
            },
        },
        volumes => { map{("vol-c4-$_"=>{})} qw[db4] },
    });
    my $services_of_stacks = {
        (map{("$docker_build/$_" => {%base_stack,
            app=>&$app_staged("gate-consumer-example",$_)
        })}
            "ee.cone.c4gate.TestConsumerApp",
            "ee.cone.c4gate.TestSerialApp",
            "ee.cone.c4gate.TestParallelApp",
        ),
        (map{("$docker_build/$$_[0]" => {%base_ui_stack,
            map{($$_[0]=>&$app_staged("gate-sse-example",@$_))} @{$$_[1]||die}
        })}
            ["test_sse"=>[["ee.cone.c4gate.TestSSEApp"]]], # http://localhost:8067/sse.html#
            ["test_ui"=>[
                ["ee.cone.c4gate.TestTodoApp"],
                ["ee.cone.c4gate.TestCoWorkApp"],
                ["ee.cone.c4gate.TestCanvasApp"],
                ["ee.cone.c4gate.TestPasswordApp"],
            ]]
        ),

    };
    for my $path(sort keys %$services_of_stacks){
        $path=~m{/}||die $path;
        &$put_yml("$path.yml",{services=>$$services_of_stacks{$path}||die});
    }
};

my $gen_docker_images = sub{
    for my $ctx_dir(sort grep{-e "$_/Dockerfile"} <$docker_build/*>){
        my $name = substr $ctx_dir, 1+length $docker_build;
        sy("docker build -t $registry_prefix-$name $ctx_dir");
        so("docker push $registry_prefix-$name");
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
push @tasks, ["build_images_only", sub{
    my($mode) = @_;
    &$gen_docker_conf();
    &$gen_docker_images();
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
    sy("docker-compose -p $project -f $docker_build/$name.yml -f $docker_build/override.yml up -d --remove-orphans")
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
#push @tasks, ["test_c3_up", sub{ &$staged_up("c3") }];

################################################################################

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{$$_[0]=~/^\./ ? () : $$_[0]=~"^#" ? $$_[0] : "  $0 $$_[0]"} @tasks;
}
