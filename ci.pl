#!/usr/bin/perl
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syl{ print "$_\n" and return `$_` for @_ }
my $env = sub{ $ENV{$_[0]} || die "no $_[0]" };
my $serve = sub{
    my($port,$handle)=@_;
    use strict;
    use IO::Socket::INET;
    my $socket = new IO::Socket::INET(
        LocalHost => '0.0.0.0',
        LocalPort => $port,
        Proto => 'tcp',
        Listen => 5,
        Reuse => 1
    ) or die $!;
    while(1){
      my $client_socket = $socket->accept();
      my $fileno = $client_socket->fileno;
      open STDIN, "<&$fileno" or die;
      open STDOUT, ">&$fileno" or die;
      open STDERR, ">&$fileno" or die;
      &$handle();
      shutdown($client_socket, 1);
    }
    $socket->close();
};
my $ctx_dir = "/tmp/c4ctx-$$";
my $clear_ctx = sub{ sy("rm -r $ctx_dir; mkdir -p $ctx_dir"); };
sy("test -e /usr/bin/docker || curl https://get.docker.com/ | sh");
my $repo_dir = &$env("C4CI_REPO_DIR");
&$serve(&$env("C4CI_PORT"),sub{
    my $full_img = <STDIN> || die;
    chomp $full_img;
    my($tag,$base,$mode,$checkout) =
        $full_img=~/^[\w\-\.\:]+\:(([\w\-\.]+)\.(\w+)\.([\w\-]+))$/ ?
        ($1,$2,$3,$4) : die "bad tag: $full_img";
    &$clear_ctx();
    sy("cd $repo_dir && git fetch && git fetch --tags");
    sy("git --git-dir=$repo_dir/.git --work-tree=$ctx_dir checkout $checkout -- .");
    #we can implement fork after checkout later and unshare ctx_dir
    my $args = " --build-arg C4CI_FULL_IMG=$full_img --build-arg C4CI_BASE_TAG=$base";
    sy("docker build -t builder:$tag -f build.$mode.dockerfile $args $ctx_dir");
    &$clear_ctx();
    sy("docker create --name builder-$$ builder:$tag");
    sy("docker cp builder-$$:/c4res $ctx_dir");
    sy("docker rm -f builder-$$");
    sy("docker build -t $full_img $ctx_dir");
    sy("docker push $full_img");
});

=sk
version: "3.2"
services:
  ci:
    image: ubuntu
    userns_mode: "host"
    command: ["sh","-c","test -e /c4proto || git clone git@github.com:conecenter/c4proto.git; perl /ci.pl"] #inst doc; clone
    restart: unless-stopped
    volumes:
    - /var/run/docker.sock:/var/run/docker.sock
    - ./ci.pl:/ci.pl
    environment:
    - C4CI_REPO_DIR=/c4proto
    - C4CI_PORT=7079
=cut