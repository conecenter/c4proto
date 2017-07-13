
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }

my $zoo_port = 2181;
my $bin = "kafka/bin";
my $zoo_host = "zookeeper";
my $kafka_topics = "$bin/kafka-topics.sh --zookeeper $zoo_host:$zoo_port --topic .inbox";
sy("$kafka_topics --create --partitions 1 --replication-factor 1 --force")
    if 0 > index syf("$kafka_topics --list"), ".inbox";
my $kafka_configs = "$bin/kafka-configs.sh --zookeeper $zoo_host:$zoo_port --entity-type topics ";
my $infinite_lag = "min.compaction.lag.ms=9223372036854775807";
my $compression = "compression.type=producer";
sy("$kafka_configs --alter --entity-name .inbox --add-config $infinite_lag,$compression");
die if 0 > index syf("$kafka_configs --describe --entity-name .inbox"),$infinite_lag;

sleep 3600;