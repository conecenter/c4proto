package ee.cone.c4assemble;

import scala.Product;

public class HashCodeCache {
    private final Product[] cacheItems;
    private final int[] cacheHashes;
    public HashCodeCache(int size){
        cacheItems = new Product[size];
        cacheHashes = new int[size];
    }
    public int get(Product item){
        if(cacheItems.length == 0) return item.hashCode();
        final int pos = System.identityHashCode(item) % cacheItems.length;
        if(cacheItems[pos] == item) return cacheHashes[pos];
        int hash = item.hashCode();
        cacheItems[pos] = item;
        cacheHashes[pos] = hash;
        return hash;
    }
}
