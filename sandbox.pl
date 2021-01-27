
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
    my $path = $ENV{C4DEPLOY_CONF} || die "no C4DEPLOY_CONF";
    my $dir = "/tmp/c4deploy-conf";
    sy("mkdir -p $dir /c4/dropbear /c4/.ssh && cd $dir && tar -xzf $path");
    my $a_keys = "/c4/.ssh/authorized_keys";
    &$put_text($a_keys, $ENV{C4AUTHORIZED_KEYS_CONTENT} || die);
    sy("cat $dir/id_rsa.pub >> $a_keys && chmod 0700 /c4/.ssh $a_keys");
    &$put_text("/c4p_alias.sh", join "", map{"$_\n"}
        'export PATH=$PATH:/tools/jdk/bin:/tools/sbt/bin:/tools/node/bin:/tools:/c4/.bloop',
        'export JAVA_HOME=/tools/jdk',
        'export JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport -Xss16m"',
        qq[alias prod="ssh-agent perl $ENV{C4CI_PROTO_DIR}/prod.pl "],
    );
    sy("export C4AUTHORIZED_KEYS_CONTENT= ; export -p | grep ' C4' >> /c4p_alias.sh");
    grep{/c4p_alias/} syl("cat /c4/.profile") or sy("echo '. $ENV{C4CI_PROTO_DIR}/c4p_alias.sh' >> /c4/.profile");
    &$exec('dropbear', '-RFEmwgs', '-p', $ENV{C4SSH_PORT}||die 'no C4SSH_PORT');
};
my $init = sub{
    my $sock = "/c4/supervisor.sock";
    &$put_text("/c4/supervisord.conf", join '', map{"$_\n"}
        "[supervisord]",
        "nodaemon=true",
        "logfile=/dev/null",
        "logfile_maxbytes=0",
        "[unix_http_server]",
        "file=$sock",
        "[supervisorctl]",
        "serverurl=unix://$sock",
        "[rpcinterface:supervisor]",
        "supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface",
        (map{(
            "[program:$_]",
            "command=perl /sandbox.pl $_",
            "autorestart=true",
            "stderr_logfile=/dev/stderr",
            "stderr_logfile_maxbytes=0",
            "stdout_logfile=/dev/stdout",
            "stdout_logfile_maxbytes=0",
        )} qw[frpc bloop sshd])
    );
    &$exec("supervisord","-c","/c4/supervisord.conf")
};
my $cmd_map = {
    frpc => $serve_frpc,
    bloop => $serve_bloop,
    sshd => $serve_sshd,
    main => $init,
};
$$cmd_map{$ARGV[0]}->();