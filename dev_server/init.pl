
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

sy("/replink.pl");
&$exec("perl","$ENV{C4DS_PROTO_DIR}/dev_server/serve.pl","init");
