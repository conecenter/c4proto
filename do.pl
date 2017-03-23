#!/usr/bin/perl

use strict;



my $port_prefix = $ENV{C4PORT_PREFIX} || 8000;
my $http_port = $port_prefix+67;
my $sse_port = $port_prefix+68;
my $zoo_port = $port_prefix+81;
my $kafka_port = $port_prefix+92;
my $build_dir = "./client/build/test";
my $inbox_prefix = '';
my $kafka = "kafka_2.11-0.10.1.0";
my $curl_test = "curl http://127.0.0.1:$http_port/abc";
my $bootstrap_server = "127.0.0.1:$kafka_port";


sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my @tasks;

push @tasks, ["setup_sbt", sub{
    (-e $_ or mkdir $_) and chdir $_ or die for "tmp";
    my $sbta = "sbt-0.13.13.tgz";
    if(!-e $sbta){
        sy("wget https://dl.bintray.com/sbt/native-packages/sbt/0.13.13/$sbta");
        sy("tar -xzf $sbta");
        sy("./sbt-launcher-packaging-0.13.13/bin/sbt update")
    }

    #my $nodea = "node-v6.10.0-linux-x64.tar.xz";
    #if(!-e $nodea){
    #    sy("wget https://nodejs.org/dist/v6.10.0/$nodea");
    #    sy("tar -xJf $nodea");
    #}
    #print qq{export PATH=tmp/sbt-launcher-packaging-0.13.13/bin:tmp/node-v6.10.0-linux-x64/bin:\$PATH\n};
    print qq{add to .bashrc or so:\nexport PATH=tmp/sbt-launcher-packaging-0.13.13/bin:\$PATH\n};
}];
push @tasks, ["setup_kafka", sub{
    (-e $_ or mkdir $_) and chdir $_ or die for "tmp";
    if (!-e $kafka) {
        sy("wget http://www-eu.apache.org/dist/kafka/0.10.1.0/$kafka.tgz");
        sy("tar -xzf $kafka.tgz")
    }
}];


push @tasks, ["es_examples", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
}];
push @tasks, ["not_effective_join_bench", sub{
    sy("sbt 'c4actor-base-examples/run-main ee.cone.c4actor.NotEffectiveAssemblerTest' ");
}];


push @tasks, ["start_kafka", sub{
    &$put_text("tmp/zookeeper.properties","dataDir=db4/zookeeper\nclientPort=$zoo_port\n");
    &$put_text("tmp/server.properties",join "\n",
        "listeners=PLAINTEXT://$bootstrap_server",
        "log.dirs=db4/kafka-logs",
        "zookeeper.connect=127.0.0.1:$zoo_port",
        "log.cleanup.policy=compact",
        "message.max.bytes=3000000" #seems to be compressed
    );
    sy("tmp/$kafka/bin/zookeeper-server-start.sh -daemon tmp/zookeeper.properties");
    sy("tmp/$kafka/bin/kafka-server-start.sh -daemon tmp/server.properties");
}];
push @tasks, ["stop_kafka", sub{
    sy("tmp/$kafka/bin/kafka-server-stop.sh")
}];
push @tasks, ["inbox_log_tail", sub{
    sy("tmp/$kafka/bin/kafka-console-consumer.sh --bootstrap-server $bootstrap_server --topic $inbox_prefix.inbox.log")
}];
push @tasks, ["inbox_test", sub{
    sy("tmp/kafka_2.11-0.10.1.0/bin/kafka-verifiable-consumer.sh --broker-list $bootstrap_server --topic $inbox_prefix.inbox --group-id dummy-".rand())
}];


my $client = sub{
    my($inst)=@_;
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
    sy("$env ".staged("c4gate-server","ee.cone.c4gate.HttpGatewayApp"));
}];

push @tasks, ["test_post_get_tcp_service_run", sub{
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestConsumerApp"))
}];
push @tasks, ["test_post_get_check", sub{
    my $v = int(rand()*10);
    sy("$curl_test -X POST -d $v");
    sleep 1;
    sy("$curl_test");
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
    sy("$curl_test -X POST") for 0..11;
}];
push @tasks, ["test_big_message_check", sub{
    sy("dd if=/dev/zero of=tmp/test.bin bs=1M count=4 && $curl_test -v -XPOST -T tmp/test.bin")
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
    sy("$env C4PUBLISH_DIR=$build_dir C4PUBLISH_THEN_EXIT='' ".staged("c4gate-sse-example","ee.cone.c4gate.TestCanvasApp"))
}];
push @tasks, ["test_ui_password_service_run", sub{
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestPasswordApp"))
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


#tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh --zookeeper 127.0.0.1:8081 --list

#force compaction:?
#min.cleanable.dirty.ratio=0.01
#segment.ms=100
#delete.retention.ms=100

#tmp/kafka_2.11-0.10.1.0/bin/kafka-configs.sh --zookeeper 127.0.0.1:8081 --entity-type topics --describe
#tmp/kafka_2.11-0.10.1.0/bin/kafka-configs.sh --zookeeper 127.0.0.1:8081 --entity-type topics --entity-name .inbox --alter --add-config min.compaction.lag.ms=9223372036854775807
#tmp/kafka_2.11-0.10.1.0/bin/kafka-configs.sh --zookeeper 127.0.0.1:8081 --entity-type topics --entity-name .inbox.log --alter --add-config min.compaction.lag.ms=1000,segment.ms=60000,delete.retention.ms=60000