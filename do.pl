#!/usr/bin/perl

use strict;

my $gen_dir = "."; #"target/c4gen/res";
my $get_curl_test = sub{
    my $http_server = $ENV{C4HTTP_SERVER}||die 'no C4HTTP_SERVER';
    "curl $http_server/abc";
};

sub sy{
    print "$ENV{PATH}\n";
    print join(" ",@_),"\n"; system @_ and die $?;
}
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $need_tmp = sub{ -e $_ or mkdir $_ or die for "tmp" };
my $to_parent = sub{ map{ m{^(.+)/[^/]+$} ? ("$1"):() } @_ };

my @tasks;
my $main = sub{
    my($cmd,@args)=@_;
    ($cmd||'') eq $$_[0] and $$_[1]->(@args) for @tasks;
};

my $exec_server = sub{
    my($arg)=@_;
    my ($nm,$mod,$cl) = $arg=~/^(\w+)\.(.+)\.(\w+)$/ ? ($1,"$1.$2","$2.$3") : die;
    my $tmp = "target/c4";
    my $proto_lib = &$single($to_parent("$0"));
    sy("perl $proto_lib/compile.pl $mod");
    &$exec(". $tmp/mod.$mod.classpath.sh && C4ELECTOR_CLIENT_ID= C4STATE_TOPIC_PREFIX=$nm C4APP_CLASS=$cl exec java ee.cone.c4actor.ServerMain");
};
push @tasks, ["run", $exec_server];

push @tasks, ["test", sub{
    my @arg = @_;
    print map{
        my $src_dir = $_;
        my $src_mod = $src_dir=~m{([^/]+)/src$} ? $1 : die;
        my $src_files = join(" ", grep{m"/c4gen\.scala$"} `find $src_dir`=~/(\S+)/g) || die;
        map{"\t$0 run $src_mod.$_\n"} map{/(\S+)/g} `cat $src_files`=~/C4APPS:([^\n]+)/g;
    } grep{-e $_} map{"$_/src"} grep{/example/} <$gen_dir/*>;
}];

push @tasks, ["test_client",sub{
    my @arg = @_;
    if(@arg==0){
        print map{$$_[0]=~/^test_client\s/ ? "\t$0 $$_[0]\n":()} @tasks;
    } elsif(@arg==1){
        &$main("test_client $arg[0]")
    } else { die }
}];
push @tasks, ["test_client ee.cone.c4gate.TestConsumerApp", sub{
    my $v = int(rand()*10);
    my $curl_test = &$get_curl_test();
    sy("$curl_test -X POST -d $v");
    sleep 1;
    sy("$curl_test -v");
    sleep 4;
    sy("$curl_test -v");
    print " -- should be posted * 3\n";
}];
push @tasks, ["test_client ee.cone.c4gate.TestParallelApp", sub{
    my $curl_test = &$get_curl_test();
    sy("$curl_test -X POST") for 0..11;
}];
push @tasks, ["test_client post_big_message", sub{
    &$need_tmp();
    my $curl_test = &$get_curl_test();
    sy("dd if=/dev/zero of=tmp/test.bin bs=1M count=4 && $curl_test -v -XPOST -T tmp/test.bin")
}];

push @tasks,["",sub{
    print "usage:\n";
        $$_[0] && $$_[0]!~/\s/ and print "\t$0 $$_[0]\n" for @tasks;
}];

&$main(@ARGV);



#export PATH=$HOME/tools/jdk/bin:$HOME/tools/sbt/bin:$PATH
#sbt show compile:dependencyClasspath
#... ScalaCheck, Specs2, and ScalaTest

# http://localhost:8067/sse.html#
#http://localhost:8067/react-app.html#todo
#http://localhost:8067/react-app.html#rectangle
#http://localhost:8067/react-app.html#leader

# TestCanvasApp C4PUBLISH_DIR=$build_dir need?

#tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh --zookeeper 127.0.0.1:8081 --list

#force compaction:?
#min.cleanable.dirty.ratio=0.01
#segment.ms=100
#delete.retention.ms=100

#tar cvf - db4 | lz4 - db.tar.lz4
#lz4 -d db.tar.lz4 | tar xf -

#push @tasks, ["test_tcp_check", sub{
#    sy("nc 127.0.0.1 $sse_port");
#}];

#topic integrity:
#use strict;
#use JSON::XS;
#my $e = JSON::XS->new;
#my $n = 0;
#my $c = 0;
#while(<>){
#  /records_consumed/ or next;
#  my $j = $e->decode($_);
#  $$j{name} eq "records_consumed" or next;
#  my($count,$min,$max) = @{$$j{partitions}[0]}{qw(count minOffset maxOffset)};
#  $count-1 == $max-$min or die $_;
#  $n == $min or die $_;
#  $n = $max + 1;
#  $c += $count;
#}
#print "count:$c\n";

