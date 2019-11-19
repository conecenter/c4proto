#!/usr/bin/perl
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }
my $sy_in_dir = sub{ my($d,$c)=@_; sy("mkdir -p $d && cd $d && $c") };
my $pwd = lazy{ my $c = `pwd`; chomp $c; $c };
my $parents; $parents = sub{ ($_[0], $_[0]=~m{(.+)/[^/]+} ? &$parents("$1"):()) };

my $clear = sub{
    my $dir = &$pwd();
    my @found = `find $dir`;
    for(reverse sort @found){
        my $path = /^(.+)\n$/ ? $1 : die "[$_]";
        my $pre = m{(.+)/(target|tmp|node_modules|build)/} ? $1 : next;
        if(grep{-e "$_/build.sbt"} &$parents($pre)){
            unlink $path or rmdir $path or warn "can not clear '$path'";
        } else {
            warn "do we need to clear '$path'?";
        }
    }
};

my $run_generator_outer = sub{
    my $src_dir = &$pwd();
    my $generator_path = "$src_dir/generator/target/c4gen";
    (-e "$generator_path/src") ? sy("rm -r $generator_path/src") : sy("mkdir -p $generator_path");
    my $src_dirs = join " ", grep{-e $_} map{"$_/src"} <$src_dir/c4*>;
    sy("find $src_dirs -type f | sort > $generator_path/src");
    &$sy_in_dir("$src_dir/generator","C4GENERATOR_PATH=$generator_path perl run.pl");
};

#my $exclude = join " ", map{"--exclude='$_'"} 'target','tmp','node_modules','build','.git','.idea','generator';

my $build_do = sub{
   my $dir = &$pwd();
   my $gen_dir = $dir;
    &$run_generator_outer();
   #see /universal/
   #my $gen_dir = "$dir/tmp/c4proto";
   #sy("mkdir -p $gen_dir");
   #sy("rsync -av --del $exclude $dir/ $gen_dir");
   &$sy_in_dir($gen_dir,"sbt stage");
};

my $build_some_server = sub{
    my($clean)=@_;
    &$clear() if $clean;
    my $port = $ENV{C4BUILD_PORT}-0;
    print "C4BUILD_PORT: $port\n";
    return &$build_do() if !$port;
    my $dir = &$pwd();
    my $rdir = "/c4/c4proto";
    local $ENV{C4BUILD_CLEAN} = $clean;
    local $ENV{C4BUILD_CMD} = ". /c4p_alias.sh && cd $rdir && perl app.pl build_do";
    sy("perl $dir/generator/sync.pl $dir $rdir");
};

my @tasks;
push @tasks, ["### build ###"];
push @tasks, ["build_all", sub{ &$build_some_server(1); }];
push @tasks, ["build_some_server", sub{ &$build_some_server(0); }];
push @tasks, ["build_do", sub{ &$build_do() }];

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{$$_[0]=~/^\./ ? () : $$_[0]=~"^#" ? $$_[0] : "  $0 $$_[0]"} @tasks;
}
