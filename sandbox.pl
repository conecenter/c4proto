
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $serve_frpc = sub{ &$exec("/tools/frp/frpc", "-c", $ENV{C4FRPC_INI}||die) };
my $serve_bloop = sub{ &$exec("bloop","server") };
my $serve_sshd = sub{
    &$put_text("/c4/authorized_keys", $ENV{C4AUTHORIZED_KEYS_CONTENT} || die);
    sy("cat /id_rsa.pub /c4/authorized_keys > /c4/.ssh/authorized_keys");
    &$put_text("/c4/c4env",join'',grep{/ C4/ and m'"\s*$' || m"'\s*"} syl("export -p | cat"));
    &$exec('dropbear', '-RFEmwgs', '-p', $ENV{C4SSH_PORT}||die 'no C4SSH_PORT');
};
my $init = sub{ &$exec("supervisord","-c","/c4/supervisord.conf") };
my $cmd_map = {
    frpc => $serve_frpc,
    bloop => $serve_bloop,
    sshd => $serve_sshd,
    main => $init,
};
$$cmd_map{$ARGV[0]}->();