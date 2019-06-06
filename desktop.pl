
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

my @tasks;

push @tasks, [frpc=>sub{
    if($ENV{C4DATA_DIR}){
        &$need_home();
        m{^/c4repo/(\w+)$} and !-e "/c4/$1" and sy("ln -s $_ /c4/$1") for </c4repo/*>;

    }
    &$exec("/tools/frp/frpc", "-c", $ENV{C4FRPC_INI}||die);
}];
push @tasks, [fix=>sub{
    my $data_dir = $ENV{C4DATA_DIR} || die;
    sy($_) for
        'mv /c4 /tools/skel', "ln -s $data_dir/home /c4",
        #desktop
        'echo "allowed_users=anybody" > /etc/X11/Xwrapper.config',
        'cp /etc/X11/spiceqxl.xorg.conf /etc/X11/c4spiceqxl.xorg.conf',
        'chown c4:c4 /etc/X11/c4spiceqxl.xorg.conf',
        q[perl  -i -pe 's{(/python\n)}{$1\ntemp_dir=None\n}' /usr/bin/Xspice];
}];
my $need_home = sub{
    print "1\n";
    my $data_dir = $ENV{C4DATA_DIR} || die;
    sy("mkdir -p $data_dir/home && chmod 0700 $data_dir/home");
    print "2\n";
};
push @tasks, [desktop=>sub{
    &$need_home();
    sy("mkdir -p /c4/.config/autostart");
    my $pass_fn = $ENV{C4AUTH_KEY_FILE} || die;
    my $pass = `cat $pass_fn`=~/(\S+)/ ? $1 : die;
    my $conf_fn = "c4spiceqxl.xorg.conf";
    my $id = 1979;
    my %opt = (
        SpicePassword=>$pass,
        SpiceVdagentEnabled=>"True", SpiceVdagentUid=>$id, SpiceVdagentGid=>$id
    );
    my $conf_cont = join '',
        map{/(Option "(\w+)")/ && (exists $opt{$2})?($_,qq[$1 "$opt{$2}"\n]):$_}
        `cat /etc/X11/spiceqxl.xorg.conf`;
    &$put_text("/etc/X11/$conf_fn", $conf_cont);
    my $agent = "/c4/vdagentd";
    &$put_text($agent,"#!/usr/bin/perl\nexec 'spice-vdagentd','-X',\@ARGV;die");
    sy('chmod', '+x', $agent);
    my @vdagent = (
        '--vdagent',
        '--vdagentd-exec' => $agent,
        '--vdagent-uid' => $id,
        '--vdagent-gid' => $id,
        '--vdagent-virtio-path' => '/tmp/xspice-virtio',
        '--vdagent-uinput-path' => '/tmp/xspice-uinput',
    );#--vdagent-no-launch
    &$exec("Xspice",@vdagent,"--config",$conf_fn,"--xsession","openbox",":1"); #-session
}];
push @tasks, [sshd=>sub{
    &$need_home();
    chomp, m{^/tools/skel(/.\w+)$} and !-e "/c4$1" and sy("cp",$_,"/c4$1") for </etc/skel/.*>;
    sy('mkdir -p /c4/dropbear /c4/.ssh');
    -e "/c4/.ssh/authorized_keys" or sy("cat /id_rsa.pub >> /c4/.ssh/authorized_keys");
    sy('chmod 0700 /c4/.ssh /c4/.ssh/authorized_keys');
    grep{/c4p_alias/} `cat /c4/.profile` or sy("echo '. /c4p_alias.sh' >> /c4/.profile");
    #sy('test -e /c4/c4proto || git clone https://github.com/conecenter/c4proto.git /c4/c4proto');
    &$exec('dropbear', '-RFEmwgs', '-p', $ENV{C4SSH_PORT}||die 'no C4SSH_PORT');
}];
push @tasks, [haproxy=>sub{
    &$need_home();
    my $port_prefix = $ENV{C4PORT_PREFIX} || 8000;
    $ENV{C4HTTP_PORT} = $port_prefix+67;
    $ENV{C4SSE_PORT} = $port_prefix+68;
    &$exec("perl", "/haproxy.pl");
}];

my($cmd,@args)=@ARGV;
$cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
