
use strict;

my $dir = ($ENV{C4CI_PROTO_DIR}||die)."/agent";
system "cd $dir && sbt c4build" and die $?;
exec "java","-cp",(scalar `cat $dir/target/c4classpath`),"Main",@ARGV;die;
