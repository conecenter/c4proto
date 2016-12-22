#!/usr/bin/perl

use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my @tasks;

push @tasks, ["es_examples", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
}];

push @tasks, ["setup_kafka", sub{
    (-e $_ or mkdir $_) and chdir $_ or die for "tmp";
    sy("wget http://www-eu.apache.org/dist/kafka/0.10.1.0/kafka_2.11-0.10.1.0.tgz");
    sy("tar -xzf kafka_2.11-0.10.1.0.tgz")
}];
push @tasks, ["run_kafka", sub{
    chdir "tmp/kafka_2.11-0.10.1.0" or die $!;
    sy("bin/zookeeper-server-start.sh config/zookeeper.properties 1> zookeeper.log 2> zookeeper.error.log &");
    sy("bin/kafka-server-start.sh config/server.properties 1> kafka.log 2> kafka.error.log &");
}];
push @tasks, ["gate_server_stage", sub{
    sy("sbt clean c4gate-server/stage");
}];
push @tasks, ["gate_server_run", sub{
    sy("C4BOOTSTRAP_SERVERS=localhost:9092 C4STATE_TOPIC_PREFIX=http-gate-0 C4HTTP_PORT=8067 C4SSE_PORT=8068 c4gate-server/target/universal/stage/bin/c4gate-server");
}];
push @tasks, ["gate_consumer_run", sub{
    sy("C4BOOTSTRAP_SERVERS=localhost:9092 C4STATE_TOPIC_PREFIX=http-test-0 sbt c4gate-consumer-example/run")
}];
push @tasks, ["gate_post_get_check", sub{
    my $v = int(rand()*10);
    sy("curl http://127.0.0.1:8067/abc -X POST -d $v");
    sleep 1;
    sy("curl http://127.0.0.1:8067/abc");
}];
push @tasks, ["gate_sse_check", sub{
    sy("nc 127.0.0.1 8068");
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
