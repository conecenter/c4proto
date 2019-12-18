#!/usr/bin/perl
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

my $sync = sub{
    my $dir = `pwd`=~/^(\S+)\s*$/ ? $1 : die;
    sy("perl $dir/sync.pl start $dir /c4/c4proto '$ENV{C4BUILD_PORT}'");
    sy("perl $dir/sync.pl run $dir 'perl build.pl'");
    sy("perl $dir/sync.pl back $dir");
    $dir;
};

my @tasks;
push @tasks, ["","",sub{
    print join '', map{"$_\n"} "usage:",
        (map{!$$_[1] ? () : "  $0 $$_[0] $$_[1]"} @tasks);
}];
push @tasks, ["build_all"," ",sub{
    local $ENV{C4BUILD_CLEAN} = 1;
    my $dir = &$sync();
    sy("perl $dir/sync.pl run $dir 'sh .bloop/c4/tag.all.compile'");
}];
push @tasks, ["build_some_server"," ",sub{ &$sync(); }];

my($cmd,@args)=@ARGV;
($cmd||"") eq $$_[0] and $$_[2]->(@args) for @tasks;
