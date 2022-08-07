package ee.cone.c4assemble;

import scala.Product;

public class RawToPrimaryKey {
    public static String get(Product p){
        while(true) {
            if (p.productArity() == 0) return "";
            Object k = p.productElement(0);
            if (k instanceof String) return (String) k;
            p = (Product) k;
        }
    }
}
