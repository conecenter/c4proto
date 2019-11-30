#!/usr/bin/perl
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

my $build_some_server = sub{
    my($clean)=@_;
    my $port = $ENV{C4BUILD_PORT}-0;
    print "C4BUILD_PORT: $port\n";
    local $ENV{C4BUILD_CMD} = "C4GENERATOR_DEP=./build.sbt perl generator/run.pl";
    local $ENV{C4BUILD_COMPILE_CMD} = "bloop compile extra_examples.aggregate";
    if($port){
        my $dir = `pwd`=~/^(\S+)\s*$/ ? $1 : die;
        local $ENV{C4BUILD_CLEAN} = $clean;
        sy("perl $dir/generator/sync.pl $dir /c4/c4proto");
    } else {
        sy($_) for $ENV{C4BUILD_CMD}, $ENV{C4BUILD_COMPILE_CMD};
    }
};

my @tasks;
push @tasks, ["","",sub{
    print join '', map{"$_\n"} "usage:",
        (map{!$$_[1] ? () : "  $0 $$_[0] $$_[1]"} @tasks);
}];
push @tasks, ["build_all"," ",sub{ &$build_some_server(1); }];
push @tasks, ["build_some_server"," ",sub{ &$build_some_server(0); }];

my($cmd,@args)=@ARGV;
($cmd||"") eq $$_[0] and $$_[2]->(@args) for @tasks;
