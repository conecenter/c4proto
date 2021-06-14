
use strict;
use JSON::XS;
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
my $serve_bloop = sub{ &$exec("bloop","server") }; #may be add: $exec_at JAVA_TOOL_OPTIONS => "-Xmx4g -Xss16m -XX:+UseG1GC",
my $serve_sshd = sub{
    do{
        my $dev_auth_dir = $ENV{C4DEV_AUTH_DIR} || die "no C4DEV_AUTH_DIR";
        my $dir = "/c4/dropbear";
        my $fn = "dropbear_ecdsa_host_key";
        sy("mkdir -p $dir && cp $dev_auth_dir/$fn $dir/ && chmod 0600 $dir/$fn");
    };
    do{
        my $dir = "/c4/.ssh";
        my $a_keys = "$dir/authorized_keys";
        sy("mkdir -p $dir && chmod 0700 $dir");
        &$put_text($a_keys, $ENV{C4AUTHORIZED_KEYS_CONTENT} || die "no C4AUTHORIZED_KEYS_CONTENT");
        sy("chmod 0600 $a_keys");
    };
    #
    my $alias_prod = qq[alias prod="perl $ENV{C4CI_PROTO_DIR}/prod.pl "];
    &$put_text("/c4p_alias.sh", join "", map{"$_\n"}
        'export PATH=$PATH:/tools/jdk/bin:/tools/sbt/bin:/tools/node/bin:/tools:/c4/.bloop',
        'export JAVA_HOME=/tools/jdk',
        'export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:-UseContainerSupport"', #-Xss16m
        'export KUBECONFIG=$C4KUBECONFIG',
        'eval `ssh-agent`',
        'history -c && history -r /c4/.bash_history_get && export PROMPT_COMMAND="history -a /c4/.bash_history_put"',
    );
    sy("export C4AUTHORIZED_KEYS_CONTENT= ; export -p | grep ' C4' >> /c4p_alias.sh");
    &$get_text_or_empty("/c4/.profile")=~/c4p_alias/ or sy("echo '. /c4p_alias.sh' >> /c4/.profile");
    &$get_text_or_empty("/c4/.bashrc")=~/alias prod=/ or sy("echo '$alias_prod' >> /c4/.bashrc");
    #
    &$exec('dropbear', '-RFEmwgs', '-p', $ENV{C4SSH_PORT}||die 'no C4SSH_PORT');
};

my $debug_port = 5005;
my $serve_proxy = sub{
    my $debug_ext_address = "127.0.0.1:$debug_port";
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
        my $to = &$get_text_or_empty("$tmp/tag.$arg.to");
        my ($nm,$mod,$cl) = $to=~/^(\w+)\.(.+)\.(\w+)$/ ? ($1,"$1.$2","$2.$3") : die "[$to]";
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
            my $paths = JSON::XS->new->decode(&$get_text_or_empty("$tmp/mod.$mod.classpath.json"));
            my $env = {
                %$paths,
                !(-e "/c4/debug-enable") ? () : (JAVA_TOOL_OPTIONS => " -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debug_int_ip:$debug_port $ENV{JAVA_TOOL_OPTIONS}"),
                C4ELECTOR_PROC_PATH => "/proc/$ppid",
                C4READINESS_PATH => "$dir/c4is-ready",
                C4STATE_TOPIC_PREFIX => $nm,
                C4APP_CLASS => $cl,
            };
            &$exec_at($dir,$env,"perl","$build_dir/run_env.pl");
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

my $serve_history = sub{
    my $env = {
        CLASSPATH => (syf("coursier fetch --classpath org.apache.kafka:kafka-clients:2.8.0")=~/(\S+)/ ? $1 : die),
        C4HISTORY_PUT => "/c4/.bash_history_put",
        C4HISTORY_GET => "/c4/.bash_history_get",
    };
    &$exec_at($ENV{C4CI_PROTO_DIR},$env,"java","--source","15","history.java");
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
            "command=perl $ENV{C4CI_PROTO_DIR}/sandbox.pl $_",
            "autorestart=true",
            "stderr_logfile=/dev/stderr",
            "stderr_logfile_maxbytes=0",
            "stdout_logfile=/dev/stdout",
            "stdout_logfile_maxbytes=0",
        )} qw[frpc bloop sshd proxy loop history])
    );
    &$exec("supervisord","-c","/c4/supervisord.conf")
};
my $cmd_map = {
    frpc => $serve_frpc,
    bloop => $serve_bloop,
    sshd => $serve_sshd,
    proxy => $serve_proxy,
    loop => $serve_loop,
    history => $serve_history,
    main => $init,
};
$| = 1;
$$cmd_map{$ARGV[0]}->();