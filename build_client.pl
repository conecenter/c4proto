
use strict;
use JSON::XS;

my $src_dir = $ARGV[0] || die;
my $bs_dir = "$src_dir/target/c4/client/src";
mkdir $bs_dir;
my $conf = JSON::XS->new->decode(join '',<STDIN>);
my %will = map{ref && $$_[0] eq "C4CLIENT" ? ("$bs_dir/$$_[1]","$src_dir/$$_[2]/src"):()} @$conf;
readlink($_) eq $will{$_} or unlink($_) or die $_ for <$bs_dir/*>;
-e $_ or symlink($will{$_}, $_) or die $! for sort keys %will;
