
use strict;
use POSIX ":sys_wait_h";

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $exec_at = sub{
    my($dir,$env,@args)=@_;
    chdir $dir or die $dir;
    $ENV{$_} = $$env{$_}, print "$_='$$env{$_}' \\\n" for keys %$env;
    &$exec(@args);
};
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $get_text_or_empty = sub{
    my($path)=@_;
    -e $path or return "";
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};
my $forever = sub{
    my ($f) = @_;
    my @state;
    @state = &$f(@state) while 1;
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
    &$get_text_or_empty("/c4/.profile")=~/c4p_alias/ or sy("echo '. /c4p_alias.sh' >> /c4/.profile");
    &$exec('dropbear', '-RFEmwgs', '-p', $ENV{C4SSH_PORT}||die 'no C4SSH_PORT');
};

my $debug_port = 5005;
my $serve_proxy = sub{
    my($debug_int_address)=@_;
    my ($debug_ext_address,$debug_port) = !$ENV{C4DEBUG_PROXY} ? (undef,undef) :
        $ENV{C4DEBUG_PROXY}=~/^([\d\.]+:(\d+))$/ ? ($1,$2) : die;
    my $debug_int_address = &$get_text_or_empty("/c4/haproxy.to");
    $debug_ext_address && $debug_int_address or &$exec("sleep","infinity");
    my $ha_cfg_path = "/c4/haproxy.cfg";
    &$put_text($ha_cfg_path, join "\n",
        "global",
        "  tune.ssl.default-dh-param 2048",
        "defaults",
        "  timeout connect 5s",
        "  timeout client  3d",
        "  timeout server  3d",
        "  mode tcp",
        "listen listen_def",
        "  bind $debug_ext_address",
        "  server s_def $debug_int_address",
    );
    &$exec("haproxy","-f",$ha_cfg_path);
};

###

my %color = qw(bright_red 91 green 32 yellow 33 bright_yellow 93 reset 0);
my $color = sub{
    my $v = $color{$_[0]};
    length $v or die $_[0];
    "\x1b[${v}m"
};
my $colored_line = sub{
    my($color_arg,$content)=@_;
    "\n".&$color($color_arg).$content.&$color('reset')."\n";
};
my $prep_empty_dir = sub{
    my($dir)=@_;
    -e $dir or mkdir $dir or die;
    my @dir_cont = <$dir/*>;
    !@dir_cont or unlink @dir_cont or die;
    $dir;
};

my $get_debug_ip = sub{
    my($pid)=@_;
    "127.1.".(($pid>>8) & 0xFF).".".($pid & 0xFF);
};

my $serve_loop = sub{ &$forever(sub{
    my ($was_ver,@active_pid) = @_;
    my $arg = $ENV{C4CI_BASE_TAG_ENV} || die "no C4CI_BASE_TAG_ENV";
    my $build_dir = $ENV{C4CI_BUILD_DIR} || die "no C4CI_BUILD_DIR";
    my $droll = "$build_dir/target/dev-rolling-";
    my $tmp = "$build_dir/.bloop/c4";
    @active_pid = grep{
        my $res = waitpid($_, WNOHANG);
        if($res != 0){
            #my $code = $? >> 8;
            #my $signal = $? & 127;
            # 10001111_00000000 for SIGTERM
            # 00000011_00000000 for exit(3)
            my $hex = sprintf("%X", $?);
            print &$colored_line(bright_yellow=>"Child $res ended with status 0x$hex");
        }
        $res == 0;
    } @active_pid;
    print "active pid list: @active_pid\n" if @active_pid > 1;
    my $last_ready = (grep{ -e "$droll$_/c4is-ready" } @active_pid)[-1];
    my $curr_ver = &$get_text_or_empty("$build_dir/target/gen-ver");
    #
    if($was_ver ne $curr_ver){
        $was_ver = $curr_ver;
        #
        my ($nm,$mod,$cl) = syf("$tmp/tag.$arg.to")=~/^(\w+)\.(.+)\.(\w+)$/ ? ($1,"$1.$2","$2.$3") : die;
        sy("cd $build_dir && perl $tmp/compile.pl $mod");
        my $build_client = $ENV{C4STEP_BUILD_CLIENT};
        $build_client and sy("$build_client dev");
        #
        my $ppid = $$;
        my $pid = fork();
        defined $pid or die;
        if(!$pid){
            my $dir = "$droll$$";
            &$prep_empty_dir($dir);
            my $debug_int_ip = &$get_debug_ip($$);
            my $env = {
                CLASSPATH => &$get_text_or_empty("$tmp/mod.$mod.classpath"),
                !$ENV{C4DEBUG_PROXY} ? () : (JAVA_TOOL_OPTIONS => " -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debug_int_ip:$debug_port $ENV{JAVA_TOOL_OPTIONS}"),
                C4ELECTOR_PROC_PATH => "/proc/$ppid",
                C4ROLLING => $dir,
                C4STATE_TOPIC_PREFIX => $nm,
                C4APP_CLASS => $cl,
            };
            &$exec_at($dir,$env,"java","ee.cone.c4actor.ServerMain");
            die;
        }
        print &$colored_line(bright_yellow=>"Spawned $pid");
        #
        my $debug_int_ip = &$get_debug_ip($pid);
        &$put_text("/c4/haproxy.to","$debug_int_ip:$debug_port");
        sy("cd /c4 && supervisorctl restart proxy");
        #
        push @active_pid, $pid;
    }
    my @to_kill = grep{ $last_ready ne $_ } @active_pid[0..@active_pid-2];
    if(@to_kill){
        print &$colored_line(bright_yellow=>"Killing: ".join(", ",@to_kill));
        kill 'TERM', @to_kill;
    }
    sleep 1;
    ($curr_ver,@active_pid);
})};
#? say Failed

####

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
            "command=perl $ENV{C4CI_PROTO_DIR}/sandbox.pl $_",
            "autorestart=true",
            "stderr_logfile=/dev/stderr",
            "stderr_logfile_maxbytes=0",
            "stdout_logfile=/dev/stdout",
            "stdout_logfile_maxbytes=0",
        )} qw[frpc bloop sshd haproxy loop])
    );
    &$exec("supervisord","-c","/c4/supervisord.conf")
};
my $cmd_map = {
    frpc => $serve_frpc,
    bloop => $serve_bloop,
    sshd => $serve_sshd,
    proxy => $serve_proxy,
    loop => $serve_loop,
    main => $init,
};
$$cmd_map{$ARGV[0]}->();