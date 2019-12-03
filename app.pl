#!/usr/bin/perl
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

my $build_some_server = sub{
    my($target)=@_;
    print "C4BUILD_PORT: $ENV{C4BUILD_PORT}\n";
    local $ENV{C4BUILD_CMD} = "perl build.pl";
    local $ENV{C4BUILD_COMPILE_CMD} = $target ? "bloop compile $target" : "";
    my $dir = `pwd`=~/^(\S+)\s*$/ ? $1 : die;
    sy("perl $dir/sync.pl $dir /c4/c4proto");
};

my @tasks;
push @tasks, ["","",sub{
    print join '', map{"$_\n"} "usage:",
        (map{!$$_[1] ? () : "  $0 $$_[0] $$_[1]"} @tasks);
}];
push @tasks, ["build_all"," ",sub{
    local $ENV{C4BUILD_CLEAN} = 1;
    &$build_some_server("extra_examples.aggregate");
}];
push @tasks, ["build_some_server"," ",sub{ &$build_some_server("base_server.ee.cone.c4gate_akka"); }];

my($cmd,@args)=@ARGV;
($cmd||"") eq $$_[0] and $$_[2]->(@args) for @tasks;
