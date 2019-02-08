
find | grep \\.scala\$ | xargs perl -i -pe 'if(/\@protocol/){ /"\@protocol/ || /\@protocol.*\sobject\s+\w+Base\b/ || s/(\@protocol.*\sobject\s+\w+)/${1}Base/ || die "?$_\n" }'
find | grep \\.scala\$ | xargs perl -i -pe 'if(/\@fieldAccess/){ /"\@fieldAccess/ || /\@fieldAccess.*\sobject\s+\w+Base\b/ || s/(\@fieldAccess.*\sobject\s+\w+)/${1}Base/ || die "?$_\n" }'
find | grep \\.scala\$ | xargs perl -i -pe 'if(/\@assemble/){ /"\@assemble/ || /\@assemble.*\sclass\s+\w+Base\b/ || s/(\@assemble.*\sclass\s+\w+)/${1}Base/ || die "?$_\n" }'
find | grep \\.scala\$ | xargs perl -i -pe 's/\bextends\s+Protocol\s*\{/{/; s/\bAssemble\s+with\b//;  s/\bextends\s+Assemble\s+\{/{/'
