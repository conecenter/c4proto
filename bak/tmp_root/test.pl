
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my $tmp = "tmp_root";

sy("mkdir -p $tmp");

for(qw[
  c4proto-di
  c4proto-api
  c4assemble-runtime
  c4actor-base
  c4actor-kafka
  c4gate-server
  c4gate-client
  c4gate-logback-static
  c4gate-logback
  c4gate-repl
  c4gate-akka
]){
  sy("cp -r $_/src $tmp/");
  sy("cp $_/build.sbt $tmp/$_.sbt") if -e "$_/build.sbt";

}

sy("cd generator && C4GENERATOR_PATH=$ENV{HOME}/c4proto/$tmp perl run.pl");
sy("cd $tmp && sbt stage");