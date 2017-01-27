#!/usr/bin/perl

use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my @tasks;

push @tasks, ["es_examples", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
}];
push @tasks, ["setup_run_kafka", sub{
    (-e $_ or mkdir $_) and chdir $_ or die for "tmp";
    my $kafka = "kafka_2.11-0.10.1.0";
    if(!-e $kafka){
        sy("wget http://www-eu.apache.org/dist/kafka/0.10.1.0/$kafka.tgz");
        sy("tar -xzf $kafka.tgz")
    }
    chdir $kafka or die $!;
    sy("bin/zookeeper-server-start.sh config/zookeeper.properties 1> zookeeper.log 2> zookeeper.error.log &");
    sy("bin/kafka-server-start.sh config/server.properties 1> kafka.log 2> kafka.error.log &");
}];
push @tasks, ["stage", sub{
    sy("sbt clean stage");
    chdir "client" or die $!;
    sy("npm install");
    sy("./node_modules/webpack/bin/webpack.js")
}];
my $http_port = 8067;
my $sse_port = 8068;
my $env = "C4BOOTSTRAP_SERVERS=localhost:9092 C4HTTP_PORT=$http_port C4SSE_PORT=$sse_port ";
sub staged{"$_[0]/target/universal/stage/bin/$_[0]"}
#sbt $_[0]/run
push @tasks, ["gate_server_run", sub{
    sy("$env C4STATE_TOPIC_PREFIX=http-gate-0 ".staged("c4gate-server"));
}];
push @tasks, ["test_post_get_tcp_service_run", sub{
    sy("$env C4STATE_TOPIC_PREFIX=http-test-0 ".staged("c4gate-consumer-example"))
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
push @tasks, ["gate_publish", sub{
    sy("$env C4PUBLISH_DIR=./client/build/test ".staged("c4gate-publish"))
}];
push @tasks, ["test_consumer_sse_service_run", sub{
    sy("$env C4STATE_TOPIC_PREFIX=sse-test-0 sbt 'c4gate-sse-example/run-main ee.cone.c4gate.TestSSE' ")
}];
push @tasks, ["test_consumer_todo_service_run", sub{
    sy("$env C4STATE_TOPIC_PREFIX=todo-test-0 sbt 'c4gate-sse-example/run-main ee.cone.c4gate.TestTodo' ")
}];
push @tasks, ["test_consumer_cowork_service_run", sub{
    sy("$env C4STATE_TOPIC_PREFIX=cowork-test-0 sbt 'c4gate-sse-example/run-main ee.cone.c4gate.TestCoWork' ")
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
