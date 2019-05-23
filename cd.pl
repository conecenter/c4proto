#!/usr/bin/perl
use strict;
use Digest::MD5 qw(md5_hex);

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
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
      shutdown($client_socket,2) or die;
    }
    $socket->close();
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
    my $auth_path = &$env("C4CD_AUTH_KEY_FILE");
    my $auth = `cat $auth_path` || die;
    &$serve(&$env("C4CD_PORT"),sub{
        my $uuid = `uuidgen`;
        $| = 1;
        print $uuid;
        my $arg = <STDIN>;
        my $signature = <STDIN>;
        md5_hex("$auth\n$uuid$arg")."\n" eq $signature or die "bad signature";
        my ($comp,$add) = $arg=~m{^run\s+(\w[\w\-]*)/up\b(.*)} ? ($1,$2) : die "can not [$arg]";
        my $dir = &$env("C4CD_DIR");
        my $args_path = "$dir/$comp.args";
        !$add or open FF, ">>", $args_path and print FF "$add\n" and close FF or die;
        sy("cd $dir/$comp && ./up");
    });
}];

###

my($cmd,@args)=@ARGV;
($cmd||'def') eq $$_[0] and $$_[1]->(@args) for @tasks;