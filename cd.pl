#!/usr/bin/perl
use strict;
use Digest::MD5 qw(md5_hex);

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $env = sub{ $ENV{$_[0]} || die "no $_[0]" };
my $log = sub{
    my($path,$data)=@_;
    use Fcntl qw(:flock);
    open FF, ">>", $path and flock FF, LOCK_EX and print FF $data and close FF or die $!;
};
my @tasks;

###

push @tasks, [frpc=>sub{
    &$exec("/tools/frp/frpc", "-c", &$env("C4FRPC_INI"));
}];
push @tasks, [sshd=>sub{
    &$exec('dropbear', '-RFEmwgs', '-p', &$env("C4SSH_PORT"));
}];
push @tasks, [kubectl=>sub{
    &$exec('kubectl', 'proxy', '--port=8080', '--disable-filter=true');
}];
push @tasks, [cd=>sub{
    my $port = &$env("C4CD_PORT");
    &$exec('socat', "tcp-l:$port,reuseaddr,fork", "exec:perl /cd.pl cd_handle");
}];
push @tasks, [cd_handle=>sub{
    my $auth_path = &$env("C4CD_AUTH_KEY_FILE");
    my $auth = `cat $auth_path` || die;
    my $uuid = `uuidgen`;
    $| = 1;
    print $uuid;
    my $arg = <STDIN>;
    my $signature = <STDIN>;
    md5_hex("$auth\n$uuid$arg")."\n" eq $signature or die "bad signature";
    my ($comp,$add) = $arg=~m{^run\s+(\w[\w\-]*)/up\b(.*)} ? ($1,$2) : die "can not [$arg]";
    my $dir = &$env("C4CD_DIR");
    $add and &$log("$dir/$comp.args","$add\n");
    sy("cd $dir/$comp && ./up");
}];

###

my($cmd,@args)=@ARGV;
($cmd||'def') eq $$_[0] and $$_[1]->(@args) for @tasks;