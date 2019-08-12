
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

###

my $conf_path = $ENV{C4HAPROXY_CONF} || die;
my $http_port = $ENV{C4HTTP_PORT} || die;
my $sse_port = $ENV{C4SSE_PORT} || die;
my $ext_port = $ENV{C4JOINED_HTTP_PORT} || die;
my $data_dir = $ENV{C4DATA_DIR};
my ($hostname,$redirect) = $ENV{C4HTTPS}=~/^(|(redirect)|https:(.+))$/ ? ($3,$2) : die;

#print "$ENV{C4HTTPS} || $hostname || $redirect\n";

my $dir = $data_dir && "$data_dir/haproxy";
my $pem_path = $dir && "$dir/cert.pem";
my $https_conf = $pem_path && $hostname ? "ssl crt $pem_path" : "";

&$put_text($conf_path, join "\n",
  "defaults",
  "  timeout connect 5s",
  "  timeout client  900s",
  "  timeout server  900s",
  "frontend fe_http",
  "  mode http",
  "  bind :$ext_port $https_conf",
  $redirect ? (
  "  acl a_letsencrypt path_beg /.well-known/acme-challenge/",
  "  redirect scheme https if !{ method POST } !a_letsencrypt",
  ) : (),
  "  acl acl_sse hdr(accept) -i text/event-stream",
  "  use_backend be_sse if acl_sse",
  "  default_backend be_http",
  "backend be_http",
  "  mode http",
  "  server se_http 127.0.0.1:$http_port",
  "backend be_sse",
  "  mode http",
  "  server se_http 127.0.0.1:$sse_port",
);
if($https_conf){
    sy("mkdir -p $dir");
    sy(
        "certbot", "certonly", "--standalone", "-n",
        "--email", 'sk@cone.ee',
        "--agree-tos",
        "-d", $hostname,
        "--config-dir", "$dir/letsencrypt",
        "--work-dir", "/tmp/le-work",
        "--logs-dir", "/tmp/le-logs",
        "--http-01-port", $http_port,
    );
    my $le_out_dir = $hostname && "$dir/letsencrypt/live/$hostname";
    sy("cat $le_out_dir/fullchain.pem $le_out_dir/privkey.pem > $pem_path");
}
&$exec("/usr/sbin/haproxy", "-f", $conf_path);