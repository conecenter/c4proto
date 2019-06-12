
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

###

my $http_port = $ENV{C4HTTP_PORT} || die;
my $sse_port = $ENV{C4SSE_PORT} || die;
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