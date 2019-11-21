#!/usr/bin/perl
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }
my $sy_in_dir = sub{ my($d,$c)=@_; sy("mkdir -p $d && cd $d && $c") };
my $pwd = lazy{ my $c = `pwd`; chomp $c; $c };

my $run_generator_outer = sub{
    my $src_dir = &$pwd();
    my $generator_path = "$src_dir/generator/target/c4gen";
    (-e "$generator_path/src") ? sy("rm -r $generator_path/src") : sy("mkdir -p $generator_path");
    my $src_dirs = join " ", map{"$src_dir/$_/src"}
        qw[base_lib base_server base_examples extra_lib extra_examples];
    sy("find $src_dirs -type f | sort > $generator_path/src");
    &$sy_in_dir("$src_dir/generator","C4GENERATOR_PATH=$generator_path perl run.pl");
};

my $build_some_server = sub{
    my($clean)=@_;
    my $port = $ENV{C4BUILD_PORT}-0;
    print "C4BUILD_PORT: $port\n";
    local $ENV{C4BUILD_CMD} = "perl app.pl build_do";
    local $ENV{C4BUILD_COMPILE_CMD} = "sbt compile";
    if($port){
        my $dir = &$pwd();
        my $rdir = "/c4/c4proto";
        local $ENV{C4BUILD_CLEAN} = $clean;
        sy("perl $dir/generator/sync.pl $dir $rdir");
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
push @tasks, ["build_do","",sub{ &$run_generator_outer(); }];

my($cmd,@args)=@ARGV;
($cmd||"") eq $$_[0] and $$_[2]->(@args) for @tasks;

