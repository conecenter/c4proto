
use strict;
my $put_text = sub{
    my($fn,$content)=@_;
    print "put_text ($fn)\n";
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $get_text = sub{
    my($path)=@_;
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};

my ($src_dir,$mod) = @ARGV;
my $tmp = "$src_dir/target/c4";
my $mod_dir = "$tmp/mod.$mod.d";
use JSON::XS;
my $json = JSON::XS->new->ascii(1)->canonical(1)->pretty(1);
my $res = {
    %{$json->decode(&$get_text("$tmp/paths.json"))||die},
    C4MODULES => &$get_text("$mod_dir/c4modules"),
    CLASSPATH => &$get_text("$mod_dir/target/c4classpath"),
};
my $sh = join "", map{"export $_=$$res{$_}\n"} sort keys %$res;
&$put_text("$tmp/mod.$mod.classpath.json", $json->encode($res));
&$put_text("$tmp/mod.$mod.classpath.sh", $sh);
