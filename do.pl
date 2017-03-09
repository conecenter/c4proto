#!/usr/bin/perl

use strict;



my $port_prefix = $ENV{C4PORT_PREFIX} || 8000;
my $http_port = $port_prefix+67;
my $sse_port = $port_prefix+68;
my $zoo_port = $port_prefix+81;
my $kafka_port = $port_prefix+92;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my @tasks;

push @tasks, ["es_examples", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
}];
push @tasks, ["not_effective_join_bench", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.NotEffectiveAssemblerTest' ");
}];
push @tasks, ["setup_run_kafka", sub{
    (-e $_ or mkdir $_) and chdir $_ or die for "tmp";
    my $kafka = "kafka_2.11-0.10.1.0";
    if(!-e $kafka){
        sy("wget http://www-eu.apache.org/dist/kafka/0.10.1.0/$kafka.tgz");
        sy("tar -xzf $kafka.tgz")
    }
    &$put_text("zookeeper.properties","dataDir=../db-zookeeper\nclientPort=$zoo_port\n");
    &$put_text("server.properties","listeners=PLAINTEXT://127.0.0.1:$kafka_port\nlog.dirs=../db-kafka-logs\nzookeeper.connect=127.0.0.1:$zoo_port\nlog.cleanup.policy=compact");
    sy("$kafka/bin/zookeeper-server-start.sh -daemon zookeeper.properties");
    sy("$kafka/bin/kafka-server-start.sh -daemon server.properties");
}];
my $client = sub{
    my($inst)=@_;
    my $build_dir = "./client/build/test";
    unlink or die $! for <$build_dir/*>;
    chdir "client" or die $!;
    sy("npm install") if $inst;
    sy("./node_modules/webpack/bin/webpack.js");# -d
    chdir ".." or die $!;
    $build_dir
};

push @tasks, ["stage", sub{
    sy("sbt clean stage");
    &$client(1);
}];

my $env = "C4BOOTSTRAP_SERVERS=127.0.0.1:$kafka_port C4INBOX_TOPIC_PREFIX='' C4HTTP_PORT=$http_port C4SSE_PORT=$sse_port ";
sub staged{
    $ENV{C4NOSTAGE}?
        "C4STATE_TOPIC_PREFIX=$_[1] sbt '$_[0]/run $_[1]'":
        "C4STATE_TOPIC_PREFIX=$_[1] $_[0]/target/universal/stage/bin/$_[0] $_[1]"
}
push @tasks, ["gate_publish", sub{
    my $build_dir = &$client(0);
    sy("$env C4PUBLISH_DIR=$build_dir ".staged("c4gate-publish","ee.cone.c4gate.PublishApp"))
}];
push @tasks, ["gate_server_run", sub{
    sy("$env ".staged("c4gate-server","ee.cone.c4gate.HttpGatewayApp"));
}];

push @tasks, ["test_post_get_tcp_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestConsumerApp"))
}];
push @tasks, ["test_post_get_check", sub{
    my $v = int(rand()*10);
    sy("curl http://127.0.0.1:$http_port/abc -X POST -d $v");
    sleep 1;
    sy("curl http://127.0.0.1:$http_port/abc");
    print " -- should be posted * 3\n";
}];
#push @tasks, ["test_tcp_check", sub{
#    sy("nc 127.0.0.1 $sse_port");
#}];
push @tasks, ["test_actor_serial_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestSerialApp"))
}];
push @tasks, ["test_actor_parallel_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestParallelApp"))
}];
push @tasks, ["test_actor_check", sub{
    sy("curl http://127.0.0.1:$http_port/abc -X POST") for 0..11;
}];


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
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestCanvasApp"))
}];


if($ARGV[0]) {
    $ARGV[0] eq $$_[0] and $$_[1]->() for @tasks;
} else {
    print "usage:\n";
    print "\t$0 $$_[0]\n" for @tasks;
}
#export PATH=$HOME/tools/jdk/bin:$HOME/tools/sbt/bin:$PATH
#sbt show compile:dependencyClasspath
#... ScalaCheck, Specs2, and ScalaTest

#http://localhost:8067/react-app.html#todo
#http://localhost:8067/react-app.html#rectangle
#http://localhost:8067/react-app.html#leader
