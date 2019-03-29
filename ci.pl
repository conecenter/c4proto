#!/usr/bin/perl
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syl{ print "$_\n" and return `$_` for @_ }
my $env = sub{ $ENV{$_[0]} || die "no $_[0]" };

my $repo_dir = &$env("C4CI_REPO_DIR");
my $interval = &$env("C4CI_INTERVAL");
my $prefix = &$env("C4CI_TAG_PREFIX");
my $data_dir = "/c4build";
my $git = "cd $repo_dir && git";
sy("test -e /usr/bin/docker || curl https://get.docker.com/ | sh");
while(1){
    sy("$git fetch --tags");
    my @tags = sort map{/^\s*([\w\-\.]+)\s*$/ ? "$1" : ()} syl("$git tag");
    my $todo = (grep{/^build\./ && !-e "$data_dir/$_"} @tags)[0];
    sleep($interval), next if !$todo;
    my $res_dir = "$data_dir/$todo";
    my $log = "2>>&1 >>$res_dir/log";
    mkdir $res_dir or die;
    sy("$git checkout $todo");
    my $opt = $todo=~/^(.+)\.\d$/ ? "-f build.next.dockerfile  "
    sy("docker build -t $todo -f build.dockerfile --build-arg C4NEXT_TAG=$todo $repo_dir $log");
    sy("docker rm -f $todo");
    sy("docker create --name $todo $todo");
    sy("docker cp $todo:/c4res $res_dir/res");
    sy("docker build -t $prefix$todo $res_dir/res $log");
    sy("docker push $prefix$todo");
}

=sk
version: "3.2"
services:
  ci:
    image: ubuntu
    userns_mode: "host"
    command: ["sh","-c","test -e /c4proto || git clone git@github.com:conecenter/c4proto.git; perl /ci.pl"] #inst doc; clone
    restart: unless-stopped
    volumes:
    - data:/c4build
    - /var/run/docker.sock:/var/run/docker.sock
    - ./ci.pl:/ci.pl
    environment:
    - C4CI_INTERVAL=5
    - C4CI_REPO_DIR=/c4proto
    - C4CI_TAG_PREFIX=c4zoo:
=cut