#!/usr/bin/perl

use strict;



my $port_prefix = $ENV{C4PORT_PREFIX} || 8000;
my $http_port = $port_prefix+67;
my $sse_port = $port_prefix+68;
my $zoo_port = $port_prefix+81;
my $plain_kafka_port = $port_prefix+92;
my $ssl_kafka_port = $port_prefix+93;
my $build_dir = "./client/build/test";
my $inbox_prefix = '';
my $kafka_version = "2.2.0";
my $kafka = "kafka_2.12-$kafka_version";
my $curl_test = "curl http://127.0.0.1:$http_port/abc";
#my $plain_bootstrap_server = "127.0.0.1:$plain_kafka_port";
my $ssl_bootstrap_server = "localhost:$ssl_kafka_port";
my $http_server = "127.0.0.1:$http_port";
my $gen_dir = "."; #"target/c4gen/res";
$ENV{PATH}.=":tmp/$kafka/bin";


sub syn{ print join(" ",@_),"\n"; system @_; }
sub sy{
    print "$ENV{PATH}\n";
    print join(" ",@_),"\n"; system @_ and die $?;
}
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $need_tmp = sub{ -e $_ or mkdir $_ or die for "tmp" };

my @tasks;

push @tasks, ["setup_sbt", sub{
    &$need_tmp();
    my $sbta = "sbt-0.13.13.tgz";
    if(!-e $sbta){
        sy("cd tmp && curl -LO https://dl.bintray.com/sbt/native-packages/sbt/0.13.13/$sbta");
        sy("cd tmp && tar -xzf $sbta");
        sy("tmp/sbt-launcher-packaging-0.13.13/bin/sbt update")
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
    &$need_tmp();
    if (!-e $kafka) {
        sy("cd tmp && curl -LO http://www-eu.apache.org/dist/kafka/$kafka_version/$kafka.tgz");
        sy("cd tmp && tar -xzf $kafka.tgz")
    }
}];


push @tasks, ["es_examples", sub{
    sy("cd $gen_dir && sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ProtoAdapterTest' ");
    sy("cd $gen_dir && sbt 'c4actor-base-examples/run-main ee.cone.c4actor.AssemblerTest' ");
    sy("C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConnTestApp cd $gen_dir && sbt 'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain' ");

}];
push @tasks, ["not_effective_join_bench", sub{
    sy("cd $gen_dir && sbt 'c4actor-base-examples/run-main ee.cone.c4actor.NotEffectiveAssemblerTest' ");
}];

my $inbox_configure = sub{
    my $kafka_topics = "kafka-topics.sh --zookeeper 127.0.0.1:$zoo_port --topic .inbox";
    sy("$kafka_topics --create --partitions 1 --replication-factor 1")
        if 0 > index syf("$kafka_topics --list"), ".inbox";
};

my $stop_kafka = sub{
    syn("kafka-server-stop.sh");
    syn("zookeeper-server-stop.sh");
};



push @tasks, ["restart_kafka", sub{
    my $data_dir = $ENV{C4DATA_DIR} || die "no C4DATA_DIR";
    &$stop_kafka();
    &$need_tmp();
    &$put_text("tmp/zookeeper.properties","dataDir=$data_dir/zookeeper\nclientPort=$zoo_port\n");
    sy("perl prod.pl need_certs $data_dir/ca cu.broker $data_dir $data_dir");
    sy("perl prod.pl need_certs $data_dir/ca cu.def $data_dir");
    &$put_text("tmp/server.properties", join '', map{"$_\n"}
        "log.dirs=$data_dir/kafka-logs",
        "zookeeper.connect=127.0.0.1:$zoo_port",
        "message.max.bytes=250000000", #seems to be compressed
        #"listeners=PLAINTEXT://$plain_bootstrap_server,SSL://$ssl_bootstrap_server",
        "listeners=SSL://$ssl_bootstrap_server",
        "inter.broker.listener.name=SSL",
        "socket.request.max.bytes=250000000",
    );
    sy("cat $data_dir/cu.broker.properties >> tmp/server.properties");
    sy("zookeeper-server-start.sh -daemon tmp/zookeeper.properties");
    sleep 5;
    sy("kafka-server-start.sh -daemon tmp/server.properties");
    sy("jps");
    #&$inbox_configure();
}];
push @tasks, ["stop_kafka", sub{&$stop_kafka()}];

#push @tasks, ["inbox_configure", sub{&$inbox_configure()}];

push @tasks, ["inbox_log_tail", sub{
    sy("kafka-console-consumer.sh --bootstrap-server $ssl_bootstrap_server --topic $inbox_prefix.inbox.log")
}];
push @tasks, ["inbox_test", sub{
    sy("kafka-verifiable-consumer.sh --broker-list $ssl_bootstrap_server --topic $inbox_prefix.inbox --group-id dummy-".rand())
}];

=sk
push @tasks, ["inbox_copy", sub{
    my $from = $ENV{C4COPY_FROM} || die "C4COPY_FROM required";
    &$need_tmp();
    &$put_text("tmp/copy.consumer.properties",join "\n",
        "group.id=dummy-".rand(),
        "bootstrap.servers=$from",
        #"enable.auto.commit=false"
    );
    &$put_text("tmp/copy.producer.properties",join "\n",
        "bootstrap.servers=$bootstrap_server",
        "compression.type=lz4",
        "max.request.size=10000000",
        #"linger.ms=1000",
        "batch.size=1000",
    );
    sy("kafka-mirror-maker.sh"
        ." --consumer.config tmp/copy.consumer.properties"
        ." --producer.config tmp/copy.producer.properties"
        .qq[ --whitelist="$inbox_prefix\\.inbox"]
        ." --num.streams 40"
        #." --queue.size 2000"
        #." --whitelist='.*'"
    );
}];
=cut

my $client = sub{
    my($inst)=@_;
    unlink or die $! for <$build_dir/*>;
    sy("cd client && npm install") if $inst;
    sy("cd client && ./node_modules/webpack/bin/webpack.js");# -d
    $build_dir
};

my $get_env = sub{
    my $data_dir = $ENV{C4DATA_DIR} || die "no C4DATA_DIR";
    my %env = (
        C4BOOTSTRAP_SERVERS => $ssl_bootstrap_server,
        C4INBOX_TOPIC_PREFIX => "",
        C4MAX_REQUEST_SIZE => 250000000,
        C4HTTP_SERVER => "http://$http_server",
        C4AUTH_KEY_FILE => "$data_dir/simple.auth",
        C4KEYSTORE_PATH => "$data_dir/cu.def.keystore.jks",
        C4TRUSTSTORE_PATH => "$data_dir/cu.def.truststore.jks",
        C4HTTP_PORT => $http_port,
        C4SSE_PORT => $sse_port,
        C4LOGBACK_XML => "$data_dir/logback.xml",
    );
    my $env = join " ", map{"$_=$env{$_}"} sort keys %env;
    ($env,%env);
};


sub staged{
    "C4STATE_TOPIC_PREFIX=$_[1] $gen_dir/$_[0]/target/universal/stage/bin/$_[0] $_[1]"
}
push @tasks, ["gate_publish", sub{
    my($env,%env) = &$get_env();
    my $build_dir = &$client(0);
    $build_dir eq readlink $_ or symlink $build_dir, $_ or die $! for "htdocs";
    sy("$env C4PUBLISH_DIR=$build_dir C4PUBLISH_THEN_EXIT=1 ".staged("c4gate-server","ee.cone.c4gate.PublishApp"))
}];
push @tasks, ["gate_server_run", sub{
    my($env,%env) = &$get_env();
    &$inbox_configure();
    sy("$env C4STATE_REFRESH_SECONDS=100 ".staged("c4gate-server","ee.cone.c4gate.HttpGatewayApp"));
}];
push @tasks, ["env", sub{
    my ($cmd,@exec) = @ARGV;
    my($env,%env) = &$get_env();
    $ENV{$_} = $env{$_} for keys %env;
    $ENV{C4STATE_TOPIC_PREFIX} || die "no actor name";
    sy(@exec);

    #perl $ENV{C4PROTO_DIR}/prod.pl

}];

#push @tasks, ["snapshot_maker_run", sub{
#    sy("$env ".staged("c4gate-server","ee.cone.c4gate.SnapshotMakerApp"));
#}];

push @tasks, ["test", sub{
    my ($arg_a,$arg_b) = @_;
    my($env,%env) = &$get_env();
    sy("$env ".staged(($ARGV[1]||die),($ARGV[2]||die)));
}];

push @tasks, ["test_post_get_tcp_service_run", sub{
    my($env,%env) = &$get_env();
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
#push @tasks, ["test_tcp_check", sub{
#    sy("nc 127.0.0.1 $sse_port");
#}];
push @tasks, ["test_actor_serial_service_run", sub{
    my($env,%env) = &$get_env();
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestSerialApp"))
}];
push @tasks, ["test_actor_parallel_service_run", sub{
    my($env,%env) = &$get_env();
    sy("$env ".staged("c4gate-consumer-example","ee.cone.c4gate.TestParallelApp"))
}];
push @tasks, ["test_actor_check", sub{
    sy("$curl_test -X POST") for 0..11;
}];
push @tasks, ["test_big_message_check", sub{
    &$need_tmp();
    sy("dd if=/dev/zero of=tmp/test.bin bs=1M count=4 && $curl_test -v -XPOST -T tmp/test.bin")
}];


push @tasks, ["test_ui_timer_service_run", sub{ # http://localhost:8067/sse.html#
    my($env,%env) = &$get_env();
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestSSEApp"))
}];
push @tasks, ["test_ui_todo_service_run", sub{
    my($env,%env) = &$get_env();
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestTodoApp"))
}];
push @tasks, ["test_ui_cowork_service_run", sub{
    my($env,%env) = &$get_env();
    sy("$env ".staged("c4gate-sse-example","ee.cone.c4gate.TestCoWorkApp"))
}];
push @tasks, ["test_ui_canvas_service_run", sub{
    my($env,%env) = &$get_env();
    sy("$env C4PUBLISH_DIR=$build_dir C4PUBLISH_THEN_EXIT='' ".staged("c4gate-sse-example","ee.cone.c4gate.TestCanvasApp"))
}];
push @tasks, ["test_ui_password_service_run", sub{
    my($env,%env) = &$get_env();
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

#tar cvf - db4 | lz4 - db.tar.lz4
#lz4 -d db.tar.lz4 | tar xf -


=topic integrity
use strict;
use JSON::XS;
my $e = JSON::XS->new;
my $n = 0;
my $c = 0;
while(<>){
  /records_consumed/ or next;
  my $j = $e->decode($_);
  $$j{name} eq "records_consumed" or next;
  my($count,$min,$max) = @{$$j{partitions}[0]}{qw(count minOffset maxOffset)};
  $count-1 == $max-$min or die $_;
  $n == $min or die $_;
  $n = $max + 1;
  $c += $count;
}
print "count:$c\n";
=cut
