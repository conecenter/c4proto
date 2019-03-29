
### here is script to run different containers in runtime/production where most of sources do not present

use strict;

my $zoo_port = 2181;
my $zoo_host = "zookeeper";
my $bootstrap_server = "broker:9092";
my $bin = "/tools/kafka/bin";
my $http_port = 8067;
my $sse_port = 8068;

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

my @tasks;
push @tasks, [zookeeper=>sub{
    &$put_text("/c4/zookeeper.properties",join "\n",
        "dataDir=db4/zookeeper",
        "clientPort=$zoo_port"
    );
    &$exec("$bin/zookeeper-server-start.sh", "zookeeper.properties");
}];
push @tasks, [broker=>sub{
    &$put_text("/c4/server.properties", join "\n",
        "listeners=PLAINTEXT://$bootstrap_server",
        "log.dirs=db4/kafka-logs",
        "zookeeper.connect=$zoo_host:$zoo_port",
        "message.max.bytes=250000000" #seems to be compressed
    );
    &$exec("$bin/kafka-server-start.sh", "server.properties");
}];
push @tasks, [haproxy=>sub{
    my $pem_path = "/c4/dummy.pem";
    if(!-e $pem_path){
        my $cert_path = "/c4/dummy.cert";
        sy(qq[openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout $cert_path.key -out $cert_path.crt -subj "/C=EE"]);
        &$put($pem_path,syf("cat $cert_path.crt $cert_path.key"));
    }
    &$put_text("/c4/haproxy.cfg", join "\n",
      "defaults",
      "  timeout connect 5s",
      "  timeout client  900s",
      "  timeout server  900s",
      "resolvers docker_resolver",
      "  nameserver dns \"127.0.0.11:53\"",
      "frontend fe80",
      "  mode http",
      "  bind :1080",
      "  acl acl_sse hdr(accept) -i text/event-stream",
      "  use_backend be_sse if acl_sse",
      "  default_backend be_http",
      "listen listen_443",
      "  mode http",
      "  bind :1443 ssl crt $pem_path",
      "  server s_http :1080",
      "backend be_http",
      "  mode http",
      "  server se_http gate:$http_port check resolvers docker_resolver resolve-prefer ipv4",
      "backend be_sse",
      "  mode http",
      "  server se_sse gate:$sse_port check resolvers docker_resolver resolve-prefer ipv4",
    );
    &$exec("/usr/sbin/haproxy", "-f", "/c4/haproxy.cfg");
}];
push @tasks, [frpc=>sub{
    &$exec("/tools/frp/frpc", "-c", $ENV{C4FRPC_INI}||die);
}];
push @tasks, [gate=>sub{
    &$exec("sh", "C4HTTP_PORT=$http_port C4SSE_PORT=$sse_port app/bin/c4gate-server");
}];
push @tasks, [fix_desktop=>sub{
    system $_ and die "$_,$?" for
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
    system 'chmod', '+x', $agent and die $?;
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
push @tasks, [def=>sub{
    m{([^/]+)$} and (-e $1 or symlink $_,$1) or die for </c4deploy/*>;
    &$exec("sh", "serve.sh");
}];

my($cmd,@args)=@ARGV;
($cmd||'def') eq $$_[0] and $$_[1]->(@args) for @tasks;
