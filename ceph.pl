
use strict;

sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }

my $ceph_auth_path = $ENV{C4CEPH_AUTH};
if($ceph_auth_path eq "/tmp/ceph.auth"){
    my $conf_dir = $ENV{C4S3_CONF_DIR}||die;
    my $content = join "&", map{"$$_[0]=$$_[1]"}
        (map{[$$_[0]=>syf("cat $conf_dir/$$_[1]")]} [url=>"address"],[id=>"key"],[pass=>"secret"]),
        [bucket=>$ENV{C4INBOX_TOPIC_PREFIX}||die];
    open FF,">",$ceph_auth_path and print FF $content and close FF or die "put_text($!)($ceph_auth_path)";
}
