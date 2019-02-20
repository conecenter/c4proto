
find | grep \\.scala\$ | xargs perl -i -pe 's/\bextends\s+Protocol\s*\{/{/; s/\bAssemble\s+with\b//;  s/\bextends\s+Assemble\s+\{/{/'
#perl -e 'for(map{[$_=>scalar `cat $_`]}map{/(.+\.scala)\s*$/?"$1":()}`find`){$$_[1]=~/\bAssemble\s*\n\s*with\b/s and print "$$_[0]\n"}'