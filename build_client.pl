
use strict;
use JSON::XS;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $get_text = sub{
    my($path)=@_;
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};

my $need_path = sub{
    my($dn)=@_;
    -d $dn or sy("mkdir -p $dn") if $dn=~s{/[^/]*$}{};
    $_[0];
};

my $single_or_undef = sub{ @_==1 ? $_[0] : undef };

my $decode = sub{ JSON::XS->new->decode(@_) };

my $if_changed = sub{
    my($path,$will,$then)=@_;
    return if (-e $path) && &$get_text($path) eq $will;
    my $res = &$then();
    &$put_text($path,$will);
    $res;
};
my $build_client = sub{
    my($dir, $mode)=@_;
    my $opt = $mode eq "fast" ? "--env fast=true --mode development" : $mode eq "dev" ? "--mode development" :
        "--mode production";
    my $build_dir = "$dir/out";
    unlink or die $! for <$build_dir/*>;
    my $conf_dir = &$single_or_undef(grep{-e} map{"$_/webpack"} <$dir/src/*>) || die;
    if(&$if_changed("$dir/package.json", &$get_text("$conf_dir/package.json"), sub{1})){
        sy("cp -r $conf_dir/patches $dir/.") if -e "$conf_dir/patches";
        sy("cd $dir && npm install --no-save --legacy-peer-deps");
    }
    sy("cd $dir && cp $conf_dir/webpack.config.js . && cp $conf_dir/tsconfig.json . && cp $conf_dir/.eslintrc.json . && node_modules/webpack/bin/webpack.js --color $opt");# -d
    &$put_text("$build_dir/publish_time",time);
    &$put_text("$build_dir/c4gen.ht.links",join"",
        map{ my $u = m"^/(.+)$"?$1:die; "base_lib.ee.cone.c4gate /$u $u\n" }
        map{ substr $_, length $build_dir }
        sort <$build_dir/*>
    );
};
my $build_client_changed = sub{
    my($dir,$mode)=@_;
    $dir || die;
    my $j_dir = "$dir/target/c4/client/src";
    my $conf = &$decode(&$get_text("$dir/c4dep.main.json"));
    my %will = map{ref && $$_[0] eq "C4CLIENT" ? ("$j_dir/$$_[1]","$dir/$$_[2]/src"):()} @$conf;
    readlink($_) eq $will{$_} or unlink($_) or die $_ for <$j_dir/*>;
    -e $_ or symlink($will{$_}, &$need_path($_)) or die $! for sort keys %will;
    my @files = syf("cd $j_dir && find -L -type f")=~/(.+)/g;
    &$if_changed("$dir/target/c4/client-sums-compiled", syf("cd $j_dir && md5sum ".join " ", sort @files), sub{
        &$build_client("$dir/target/c4/client", $mode);
    });
};

&$build_client_changed(@ARGV);