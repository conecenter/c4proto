#!/usr/bin/perl

use strict;

my $http_port = 8067;
my $sse_port = 8068;
my $zoo_port = 2181;
my $zoo_host = "zookeeper";
my $build_dir = "client/build/test";
my $kafka_version = "2.0.0";
my $kafka = "kafka_2.11-$kafka_version";
#my $curl_test = "curl http://127.0.0.1:$http_port/abc";
my $bootstrap_server = "broker:9092";
my $temp = "target";
my $docker_build = "$temp/docker_build";
my $user = "c4";
my $uid = 1979;
#my $developer = $ENV{USER} || die;

################################################################################


sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }

my $need_path; $need_path = sub{
    my($path)=@_;
    my $parent = $path=~m{(.*)/[^/]*$} ? $1 : die "path($path)";
    if(!-d $parent){
        &$need_path($parent);
        mkdir $parent or die "mkdir($parent)";
    }
    $path;
};

my $sy_in_dir = sub{ my($d,$c)=@_; &$need_path("$d/any"); sy("cd $d && $c") };

my $pwd = lazy{ my $c = `pwd`; chomp $c; $c };
my $abs_path = sub{ join '/', &$pwd(), @_ };

my $get_generator_path = sub{ &$abs_path("$temp/c4gen") };
#my $get_generated_sbt_dir = sub{ &$get_generator_path()."/res" };
my $get_generated_sbt_dir = sub{ &$pwd() };

my @tasks;

############################### image builds ###################################

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $indent = sub{ join "", map{s/\n/\n  /g; "\n$_"} @_ };
my %yml; my $yml; $yml = sub{ my($arg)=@_; $yml{ref $arg}->($arg) };
$yml{''} = sub{" '$_[0]'"};
$yml{HASH} = sub{ my($h)=@_; &$indent(map{"$_:".&$yml($$h{$_})} sort keys %$h) };
$yml{ARRAY} = sub{ my($l)=@_; &$indent(map{"-".&$yml($_)} @$l) };
my $put_yml; $put_yml = sub{
    my($path,$data)=@_;
    &$put_text($path,"#### this file is generated ####\n".&$yml($data))
};

#my $put_compose = sub{
#    my($project,$services)=@_;
#    &$put_yml("$docker_build/docker-compose.test_$project.yml",{ services => $services });
#};

my $mkdirs = sub{
    my($ctx_dir,@dirs)=@_;
    mkdir $_ or die $_ for map{"$ctx_dir/$_"} @dirs;
};

my $recycling = sub{
    !-e $_ or rename $_, &$need_path("$temp/recycle/".rand()) or die $! for @_;
};

my $run = sub{ "RUN ".join ' && ', @_ };
my $from = sub{
    my($install)=@_;
    (
        "FROM openjdk:11",
        &$run(
            "useradd --home-dir /$user --create-home --user-group --uid $uid --shell /bin/bash $user",
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

my $prepare_build = sub{
    my($name,$lines) = @_;
    my $ctx_dir = "$docker_build/$name";
    &$need_path("$ctx_dir/any");
    &$put_text("$ctx_dir/Dockerfile",join "\n", &$lines($ctx_dir));
    &$put_text("$ctx_dir/.dockerignore",".dockerignore\nDockerfile");
};

my $staged = sub{
    my($name,$f) = @_;
    ($name=>sub{
        my($ctx_dir)=@_;
        my $gen_dir = &$get_generated_sbt_dir();
        &$gcp("$gen_dir/c4$name/target/universal/stage"=>$ctx_dir,"app");
        &$mkdirs($ctx_dir,"db4");
        (&$from(""),qq{CMD ["app/bin/c4$name"]}, $f ? &$f($ctx_dir) : ())
    });
};

my $download_tgz = sub{
    my($ctx_dir,$url,$rename_from,$rename_to)=@_;
    my $fn = $url=~m{([^/]+)$} ? $1 : die;
    &$sy_in_dir("tmp","wget $url") if !-e "tmp/$fn";
    &$sy_in_dir($ctx_dir,"tar -xzf ../../../tmp/$fn");
    &$rename($ctx_dir, $rename_from, $rename_to);
};

my $gen_docker_conf = sub{
    my($commit)=@_;
    &$recycling($docker_build);
    my $build = sub{
        my($name,$lines) = @_;
        &$prepare_build($name=>sub{
            my($ctx_dir)=@_;
            (&$lines($ctx_dir),@$commit)
        })
    };
    &$build("zoo"=>sub{
        my($ctx_dir)=@_;
        &$download_tgz($ctx_dir, "http://www-eu.apache.org/dist/kafka/$kafka_version/$kafka.tgz", $kafka, "kafka");
        &$put_text("$ctx_dir/zookeeper.properties",join "\n",
            "dataDir=db4/zookeeper",
            "clientPort=$zoo_port"
        );
        &$put_text("$ctx_dir/server.properties",join "\n",
            "listeners=PLAINTEXT://$bootstrap_server",
            "log.dirs=db4/kafka-logs",
            "zookeeper.connect=$zoo_host:$zoo_port",
            #"log.cleanup.policy=compact",
            #"log.segment.bytes=250000000", #active segment is not compacting, so we reduce it
            #"log.cleaner.delete.retention.ms=3600000", #1h
            #"log.roll.hours=1", #delete-s will be triggered to remove?
            #"compression.type=uncompressed", #probably better compaction for .state topics
            "message.max.bytes=250000000" #seems to be compressed
            #see log.retention.*
        );
        &$download_tgz($ctx_dir,
            "https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz",
            "frp_0.21.0_linux_amd64", "frp"
        );
        &$mkdirs($ctx_dir,"db4");
        (&$from("rsync telnet mc"))
    });
    &$build("synced"=>sub{
        my($ctx_dir)=@_;
        &$mkdirs($ctx_dir,"db4");
        &$put_text("$ctx_dir/run.pl",join "\n",
            'm{([^/]+)$} and (-e $1 or symlink $_,$1) or die for </c4deploy/*>;',
            'exec "sh serve.sh";'
        );
        #rsync -r /c4deploy/ /c4

        my $jfr_dir = "/docker-java-home/lib/jfr";
        my $jfr_url = "https://hg.openjdk.java.net/jdk/jdk11/raw-file/76072a077ee1/src/jdk.jfr/share/conf/jfr";
        (
            &$from("rsync mc"),
            &$run(
                qq{mkdir -p $jfr_dir},
                qq{wget $jfr_url/default.jfc -O $jfr_dir/default.jfc},
                qq{wget $jfr_url/profile.jfc -O $jfr_dir/profile.jfc},
            ),
            q{CMD ["perl","run.pl"]},
        );
    });
    &$build(&$staged("gate-server"=>sub{
        my($ctx_dir)=@_;
        ("ENV C4HTTP_PORT $http_port","ENV C4SSE_PORT $sse_port")
    }));
    #
#    &$build("sshd"=>sub{
#        my($ctx_dir)=@_;
#        &$mkdirs($ctx_dir,"db4");
#        (&$from("openssh-server"), &$run("mkdir /var/run/sshd"))
#    });
    &$build("haproxy"=>sub{
        my($ctx_dir)=@_;
        &$put_text("$ctx_dir/haproxy.cfg",qq{
            defaults
              timeout connect 5s
              timeout client  900s
              timeout server  900s
            resolvers docker_resolver
              nameserver dns "127.0.0.11:53"
            frontend fe80
              mode http
              bind :80
              acl acl_sse hdr(accept) -i text/event-stream
              use_backend be_sse if acl_sse
              default_backend be_http
            listen listen_443
              mode http
              bind :443 ssl crt /c4deploy/dummy.pem
              server s_http :80
            backend be_http
              mode http
              server se_http gate:$http_port check resolvers docker_resolver resolve-prefer ipv4
            backend be_sse
              mode http
              server se_sse gate:$sse_port check resolvers docker_resolver resolve-prefer ipv4
        });
        (
            "FROM haproxy:1.7",
            "COPY haproxy.cfg /usr/local/etc/haproxy/haproxy.cfg",
        )
    });
    &$put_yml("$docker_build/empty.yml",{ version => "3.2" });
    ####
    &$build(&$staged("gate-sse-example",sub{
        my($ctx_dir)=@_;
        &$gcp($build_dir=>$ctx_dir,"htdocs");
        ()
    }));
    &$build(&$staged("gate-consumer-example"));
#    for(
#        [qw[post_get_tcp TestConsumerApp]],
#        [qw[actor_serial TestSerialApp]],
#        [qw[actor_parallel TestParallelApp]],
#    ){
#        my($project,$main)=@$_;
#        &$put_compose($project,{
#            app=>{
#                C4APP_IMAGE => "gate-consumer-example",
#                C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.$main"
#            }
#        })
#    }
#    for(
#        ["sse"=>[qw[PublishApp TestSSEApp]]], # http://localhost:8067/sse.html#
#        ["ui"=>[qw[PublishApp TestTodoApp TestCoWorkApp TestCanvasApp TestPasswordApp]]],
#    ){
#        my($project,$apps)=@$_;
#        &$put_compose($project,{
#            map{($_=>{
#                C4APP_IMAGE => "gate-sse-example",
#                C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.$_",
#                $_ eq "PublishApp" ? (restart => "on-failure") : ()
#            })} @$apps
#        });
#    }
};

my $webpack = sub{
    &$sy_in_dir("client","./node_modules/webpack/bin/webpack.js");# -d
    my $jnm = "client/build/test/metro-app.js";
    my $jsc = scalar `cat $jnm`;
    $jsc=~s/\bSymbol.for\b/Symbol['for']/g;
    $jsc=~s/\{(\s+)default:/{$1'default':/g;
    $jsc=~s/\.default\b/['default']/g;
    $jsc=~s/\.return\b/['return']/g;
    &$put_text($jnm, $jsc);
};

my $get_commit = sub{
    #`git status --porcelain`=~/\S/ and return [];
    #my $commit = `git rev-parse --verify HEAD`=~/([0-9a-f]{32})/ ? $1 : die;
    my $commit = "not_implemented";
    [qq{LABEL c4commit="$commit"}];
};

###

#my $git_need_repo = sub{
#    my($dir)=@_;
#    my $agit = ['git', "--git-dir=$dir/.git", "--work-tree=$dir"];
#    -e &$need_path("$dir/.git") or sy(@$agit, "init");
#    $agit;
#};
#my $git_add_commit = sub{
#    my($agit)=@_;
#    sy(@$agit, "add", "--all", ":/");
#    sy(@$agit, "commit", "-m-");
#};
#my $git_status = sub{
#    my($agit)=@_;
#    my $st = join ' ', @$agit, 'status', '--porcelain', ":/";
#    @{[`$st`]};
#};
#my $update_file_tree = sub{
#    my($gen_dir,$sbt_dir)=@_;
#    my $gen_git = &$git_need_repo($gen_dir);
#    my $sbt_git = &$git_need_repo($sbt_dir);
#    &$git_add_commit($gen_git) if &$git_status($gen_git,'');
#    #run(@$sbt_git, "checkout", ":/src") if git_status($sbt_git,"src"); #checkout failed to delete files
#    sy(@$sbt_git, "reset", "--hard");
#    sy(@$sbt_git, "pull", $gen_dir, "master:master"); #reset --hard failed to delete files
#};



my $run_generator = sub{
    my $generator_path = &$get_generator_path();
    #&$recycling($_) for <$generator_path/to/*>; # .git not included
    my $generator_src_dir = &$abs_path("generator");
    my $generator_exec = "$generator_src_dir/target/universal/stage/bin/generator";
    &$sy_in_dir($generator_src_dir,"sbt stage") if !-e $generator_exec;
    print "generation starting\n";
    sy("C4GENERATOR_PATH=$generator_path $generator_exec");
    print "generation finished\n";
};

my $run_generator_outer = sub{
    my $generator_path = &$get_generator_path();
    &$recycling("$generator_path/src");
    my $src_dir = &$abs_path();
    for my $path (grep{-e} map{"$_/src"} <$src_dir/c4*>){
        my $rel_path = substr $path, length $src_dir;
        symlink $path,&$need_path("$generator_path/src$rel_path") or die $!;
    }
    &$run_generator();
    #&$update_file_tree("$generator_path/to",&$get_generated_sbt_dir());
};

push @tasks, ["### build ###"];
push @tasks, ["build_all", sub{
    &$sy_in_dir(&$abs_path(),"sbt clean");
    &$sy_in_dir(&$abs_path("generator"),"sbt clean");
    &$run_generator_outer();
    &$sy_in_dir(&$get_generated_sbt_dir(),"sbt stage");
    &$sy_in_dir("client","npm install");
    &$recycling($build_dir);
    &$webpack();
    &$gen_docker_conf(&$get_commit());
}];
push @tasks, ["build_some_server", sub{
    &$run_generator_outer();
    &$sy_in_dir(&$get_generated_sbt_dir(),"sbt stage");
    &$gen_docker_conf(&$get_commit());
}];
push @tasks, ["run_generator", sub{
    &$run_generator_outer();
}];
push @tasks, ["build_some_client", sub{
    &$webpack();
    &$gen_docker_conf([]);
}];
push @tasks, ["build_conf_only", sub{
    &$gen_docker_conf([]);
}];
#push @tasks, ["sbt", sub{
#    chdir &$get_generated_sbt_dir() or die $!;
#    sy("sbt",@ARGV[1..$#ARGV]);
#}];

################################################################################

#...
#push @tasks, ["### debug ###"];
#push @tasks, ["inbox_log_tail", sub{
#    sy("$bin/kafka-console-consumer.sh --bootstrap-server $bootstrap_server --topic $inbox_prefix.inbox.log")
#}];
#push @tasks, ["inbox_test", sub{
#    sy("$bin/kafka-verifiable-consumer.sh --broker-list $bootstrap_server --topic $inbox_prefix.inbox --group-id dummy-".rand())
#}];

################################################################################

#my $staged_up = sub{
#    #build-up build-push
#    #app.yml dev-proj-ports
#    my($name)=@_;
#    ["test_$name\_up", sub{
#        #todo fix
#        #&$composer("up_local",$developer,(die),"docker-compose.test_$name.yml");
#    }];
#};
#
#
#
#push @tasks, ["### tests ###"];
#push @tasks, ["test_es_examples", sub{
#    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
#    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
#}];
#push @tasks, ["test_not_effective_join_bench", sub{
#    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.NotEffectiveAssemblerTest' ");
#}];
#
#push @tasks, &$staged_up("post_get_tcp");
#push @tasks, ["test_post_get_check", sub{
#    my $v = int(rand()*10);
#    sy("$curl_test -X POST -d $v");
#    sleep 1;
#    sy("$curl_test -v");
#    sleep 4;
#    sy("$curl_test -v");
#    print " -- should be posted * 3\n";
#}];
#push @tasks, &$staged_up("actor_serial");
#push @tasks, &$staged_up("actor_parallel");
#push @tasks, ["test_actor_check", sub{ sy("$curl_test -X POST") for 0..11 }];
#push @tasks, ["test_big_message_check", sub{
#    &$sy_in_dir($temp,"dd if=/dev/zero of=test.bin bs=1M count=4 && $curl_test -v -XPOST -T test.bin")
#}];
#push @tasks, &$staged_up("sse");
#push @tasks, &$staged_up("ui");

################################################################################

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{$$_[0]=~/^\./ ? () : $$_[0]=~"^#" ? $$_[0] : "  $0 $$_[0]"} @tasks;
}
