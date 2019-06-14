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
my $get_handler = sub{ for my $v(@_){$v eq $$_[0] and return $$_[1] for @tasks} die };

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
    my ($cmd,$left) = $arg=~m{^(\w+)\s+(.*)} ? ($1,$2) : die "no command";
    &$get_handler("cmd-$cmd",'cmd-def')->($left);
    print "\n[OK]\n";
}];
push @tasks, ["cmd-def"=>sub{ die "can not [$_[0]]" }];
push @tasks, ["cmd-run"=>sub{
    my($arg)=@_;
    my ($comp,$add) = $arg=~m{^(\w[\w\-]*)/up\b(.*)} ? ($1,$2) : die "bad run [$arg]";
    my $dir = &$env("C4CD_DIR");
    $add and &$log("$dir/$comp.args","$add\n");
    sy("cd $dir/$comp && ./up");
}];
push @tasks, ["cmd-pods"=>sub{
    my $ns = `cat /var/run/secrets/kubernetes.io/serviceaccount/namespace`=~/(\w+)/ ? "$1" : die;
    sy("kubectl -n $ns get pods -o jsonpath='pods: {.items[*].metadata.name}'");
}];
push @tasks, ["cmd-history"=>sub{
    my($arg)=@_;
    my $comp = $arg=~/^(\w[\w\-]*)$/ ? $1 : die;
    my $dir = &$env("C4CD_DIR");
    sy("echo 'history:' && cat $dir/$comp.args");
}];

###

my($cmd,@args)=@ARGV;
&$get_handler($cmd,'def')->(@args);

