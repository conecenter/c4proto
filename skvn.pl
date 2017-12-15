#!/usr/bin/perl
use strict;
use utf8;

#svn revert -R $l
#setup-s
#deploy

#C4SVN_SHORT_TRUNK = trunl
#C4SVN_REPO = https://svn.edss.ee/cone
#C4SVN_DIR = /c4/tnlib
#"C4SVN_BEFORE":"C4SVN_AFTER"

my $l = $ENV{C4SVN_DIR}||die;

sub sy{
    my $t = join ' ',@_;
    print "$t\n";
    system @_ and die "failed to $t"
}

my $l4 = sub{ $_ && sy($_) for $ENV{$_[0]?"C4SVN_BEFORE":"C4SVN_AFTER"} };
my $repo = sub{ ($ENV{C4SVN_REPO}||die)."/$_[0]" };
my $pw = sub{$_[0]=~/\.(prep|work)$/?$1:""};
my $cbr_info = sub{
    my($act)=@_;
    my $info = sub{+{map{/^([^\:]+)\:\s*(.*?)\s*$/?($1=>$2):()}`svn info $_[0]`}};
    my $i = &$info($l);
    $$i{Revision} && $$i{URL} || die;
    print "======== 8< ======== $act [$$i{URL} $$i{Revision}] ======== 8< ========\n";
    $i;
};
my $branches = sub{ map{"branches/$_"} map{sort `svn ls $_`} &$repo("branches") };
my $strunk = sub{$ENV{C4SVN_SHORT_TRUNK}||die};
my $trunk = sub{strunk()."/\n"};
my $rtrunk = sub{&$repo(strunk())};
my $check_clean = sub{
    -e $l or return;
    my @o = `svn st $l`;
    @o and die "Aborted due to local changes:\n",@o;
};
my $arg2br = sub{
    my($br)=@_;
    $br=~/[^\.\w]/ and die "bad branch name";
    $br eq 'trunk' ? &$rtrunk() : &$repo("branches/$br")
};
my $br_in = sub{ my($br,@bra)=@_; (grep{&$repo($_) eq "$br/\n"}@bra)?1:0 };
my $svnl = sub{ sy($_) for map{"svn $_ $l"} @_ };
my $sygt2 = sub{@_>2 and sy(@_)};
my $update = sub{
    ##remove for svn things, that was already removed locally
    ## need to be before update, because update puts files back
    &$sygt2('svn','rm',map{m{^!\s+([\w/\.\-]+)\n$}?$1:()}`svn st $l`);
    &$svnl('up');
};
my $mergeinfo_list = sub{`svn mergeinfo $_[0] $_[1] --show-revs $_[2]`};
my $mergeinfo = sub{&$mergeinfo_list?1:0};

sub init_sw{
    my @o = (&$branches(),&$trunk());
    @_ or return print "Available branches:\n",@o;
    &$l4(1);
    &$check_clean();
    #stop
    my $br = &$arg2br;
    &$br_in($br,@o) or die "$br does not exist.:";
    print "Sync-ing ...\n";
    &$svnl((-e $l ? 'sw':'co -q')." $br");
    &$l4();
}
sub init_brnew{
    my $i = &$cbr_info("Creating new branch from");
    @_ || die;
    my $br = &$arg2br;
    $br eq &$rtrunk() && die "can't be done with trunk";
    if(&$br_in($br,&$branches())){ 
        my @br = ($br,&$rtrunk());
        my @e = &$mergeinfo_list(@br,'eligible');
        @e and @e>1||&$mergeinfo(@br,'merged') and die "need getupd at trunk";#not just copied
        sy("svn rm $br -m skvn_auto_rm");
    }
    sy("svn cp $$i{URL}\@$$i{Revision} $br");# -m skvn_auto_cp
    &$svnl("sw $br");
    &$cbr_info("Created new branch");
    &$l4();
}
sub init_apply{
    &$l4(1);
    my $i = &$cbr_info("Applying changes to");
    my(@st,@cmt);
    my $st_auto = sub{
        @st = `svn st $l`;
        @cmt = @st==1 && $st[0]=~m{^\s*M\s+([\w/\.\-]+)\n$} && $1 eq $l 
            ? ('-m','skvn_auto_merge') : ();
    };
    ##update
    &$update();
    ##add for svn things, that was already added locally
    &$st_auto();
    my @add = map{m{^\?\s+([\w/\.\-]+)\n$}?$1:()} @st;
    my $f; $f = sub{# .svn dirs
        (-l) and die "links unsupported: $_";
        m[/\.\.?$] || !(-d) ? () : m[/\.svn$] ? $_ : map{&$f} <$_/*>, <$_/.*>;
    };
    &$sygt2('rm','-rf',map{&$f}@add);
    &$sygt2('svn','add',@add);
    ##commit and return unless prep/work branch
    my $commit = sub{
        sy('svn','ci',$l,@_);
        &$svnl('up');#last up sw-s to revision made by ci
        &$check_clean();
    };
    &$commit(@cmt);
    &$cbr_info("Applied");
    &$l4();
}
sub init_getupd{
    &$l4(1);
    my $i = &$cbr_info("Getting changes to");
    &$update();
    my $to_br = $$i{URL};
    my @o = &$pw($to_br) ? () : &$br_in($to_br,&$trunk()) ? &$branches() : &$trunk();
    @_ or return;
    my $from_br = &$arg2br;
    &$check_clean();
    &$br_in($from_br,@o) or die "branch ($from_br) is not available";
    my $o = !$from_pwt && &$mergeinfo($l,$from_br,'merged') ? '--reintegrate':'';
    $o and &$mergeinfo($l,$from_br,'eligible') and die "need getupd at $from_br";
    sy("svn merge --accept launch $o $from_br $l");
    $from_pwt or sy("svn rm $from_br -m skvn_auto_rm");
    &$cbr_info("Updated");
    &$l4();
}
sub init_st{&$cbr_info("Status of");sy("svn st $l");}
sub init_brdiff{sy("svn diff $l");}
sub init_setup{
    my $rt = $ENV{HOME};
    my $fsadd = sub{open FDL,$_[0],$_[1] and print FDL $_[2] and close FDL or die "$!:$_[1]"};
    my $aliases = join '',map{qq[alias $_='perl $rt/skvn.pl $_ '\n]} qw[apply brdiff brnew getupd st sw];
    &$fsadd(">","$rt/skvn.sh",qq{
export SVN_EDITOR="mcedit +1 ";
export SVN_MERGE="$rt/skvn_merge.pl";
$aliases
});        
    my $dir = "$rt/.local/share/mc";
    system 'mkdir','-p',$dir;
    &$fsadd(">>","$dir/bashrc",". $rt/skvn.sh");
    &$fsadd(">>","$rt/.bashrc",". $rt/skvn.sh");
}

my($cmd,@args)=@ARGV;
main->$_(@args) for "init_$cmd";
