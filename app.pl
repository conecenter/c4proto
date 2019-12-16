#!/usr/bin/perl
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

my $build_some_server = sub{
    my($target)=@_;
    local $ENV{C4BUILD_COMPILE_CMD} = $target ? "sh .bloop/c4/tag.$target.compile" : "";
    my $dir = `pwd`=~/^(\S+)\s*$/ ? $1 : die;
    sy("perl $dir/sync.pl $dir /c4/c4proto '$ENV{C4BUILD_PORT}' 'perl build.pl' ");
};

my @tasks;
push @tasks, ["","",sub{
    print join '', map{"$_\n"} "usage:",
        (map{!$$_[1] ? () : "  $0 $$_[0] $$_[1]"} @tasks);
}];
push @tasks, ["build_all"," ",sub{
    local $ENV{C4BUILD_CLEAN} = 1;
    &$build_some_server("all");
}];
push @tasks, ["build_some_server"," ",sub{ &$build_some_server("def"); }];

my($cmd,@args)=@ARGV;
($cmd||"") eq $$_[0] and $$_[2]->(@args) for @tasks;
