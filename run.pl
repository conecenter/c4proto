
### here is script to run different containers in runtime/production where most of sources do not present

use strict;

my $zoo_port = 2181;
my $zoo_host = "127.0.0.1";
my $bin = "/tools/kafka/bin";
my $http_port = 8067;
my $sse_port = 8068;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ print "$_\n" and return scalar `$_` for @_ }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

my @tasks;
push @tasks, [zookeeper=>sub{
    &$put_text("/c4/zookeeper.properties",join "\n",
        "dataDir=/c4db/zookeeper",
        "clientPort=$zoo_port",
        "clientPortAddress=$zoo_host",
    );
    &$exec("$bin/zookeeper-server-start.sh", "zookeeper.properties");
}];
push @tasks, [broker=>sub{
    my $port = $ENV{C4BOOTSTRAP_PORT} || die;
    my $add = $ENV{C4BOOTSTRAP_SERVERS} || die;
    &$put_text("/c4/server.properties", join '', map{"$_\n"}
        "log.dirs=/c4db/kafka-logs",
        "zookeeper.connect=$zoo_host:$zoo_port",
        "message.max.bytes=250000000", #seems to be compressed
        "listeners=SSL://:$port", #0.0.0.0
        "advertised.listeners=INTERNAL://127.0.0.1:$port,EXTERNAL://$add",
        "listener.security.protocol.map=SSL:SSL,INTERNAL:SSL,EXTERNAL:SSL",
    );
    my $props = $ENV{C4SSL_PROPS} || die;
    sy("cat $props >> server.properties");
    &$exec("$bin/kafka-server-start.sh", "server.properties");
}];
push @tasks, [haproxy=>sub{
    my $ext_http_port = $ENV{C4JOINED_HTTP_PORT} || die;
    my $pem_path = "/c4/dummy.pem";
    if(!-e $pem_path){
        my $cert_path = "/c4/dummy.cert";
        sy(qq[openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout $cert_path.key -out $cert_path.crt -subj "/C=EE"]);
        &$put_text($pem_path,syf("cat $cert_path.crt $cert_path.key"));
    }
    &$put_text("/c4/haproxy.cfg", join "\n",
      "defaults",
      "  timeout connect 5s",
      "  timeout client  900s",
      "  timeout server  900s",
      "frontend fe80",
      "  mode http",
      "  bind :$ext_http_port",
      "  acl acl_sse hdr(accept) -i text/event-stream",
      "  use_backend be_sse if acl_sse",
      "  default_backend be_http",
      "listen listen_443",
      "  mode http",
      "  bind :1443 ssl crt $pem_path",
      "  server s_http :$ext_http_port",
      "backend be_http",
      "  mode http",
      "  server se_http 127.0.0.1:$http_port",
      "backend be_sse",
      "  mode http",
      "  server se_http 127.0.0.1:$sse_port",
    );
    &$exec("/usr/sbin/haproxy", "-f", "/c4/haproxy.cfg");
}];
push @tasks, [frpc=>sub{
    &$exec("/tools/frp/frpc", "-c", $ENV{C4FRPC_INI}||die);
}];
push @tasks, [gate=>sub{
    mkdir "/c4db/def" and symlink "/c4db/def","db4" or die if !-e "/c4db/def";
    $ENV{C4HTTP_PORT} = $http_port;
    $ENV{C4SSE_PORT} = $sse_port;
    my $port = $ENV{C4BOOTSTRAP_PORT} || die;
    $ENV{C4BOOTSTRAP_SERVERS} = "127.0.0.1:$port";
    &$exec("app/bin/c4gate-server");
}];
push @tasks, [fix_desktop=>sub{
    sy($_) for
        'echo "allowed_users=anybody" > /etc/X11/Xwrapper.config',
        'cp /etc/X11/spiceqxl.xorg.conf /etc/X11/c4spiceqxl.xorg.conf',
        'chown c4:c4 /etc/X11/c4spiceqxl.xorg.conf',
        q[perl  -i -pe 's{(/python\n)}{$1\ntemp_dir=None\n}' /usr/bin/Xspice],
        'mkdir -p /c4/.config/autostart';
}];
push @tasks, [desktop=>sub{
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
push @tasks, [main=>sub{
    m{([^/]+)$} and (-e $1 or symlink $_,$1) or die for </c4conf/*>;
    &$exec("sh", "serve.sh");
}];

my($cmd,@args)=@ARGV;
$cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
