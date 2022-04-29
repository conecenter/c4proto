package ee.cone.c4assemble;

import java.util.Comparator;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;

public class ArrayMerger {
    static <T> int merge(T[] srcA, T[] srcB, T[] dest, Comparator<T> c, BinaryOperator<T> merger, Predicate<T> nonEmpty) {
        for (int iA = 0, iB = 0, iD = 0;;) {
            boolean moreA = iA < srcA.length;
            boolean moreB = iB < srcB.length;
            if(!moreB && !moreA) return iD;
            int sel = !moreB ? -1 : !moreA ? 1 : c.compare(srcA[iA],srcB[iB]);
            if(sel < 0) dest[iD++] = srcA[iA++];
            else if (sel > 0) dest[iD++] = srcB[iB++];
            else {
                T merged = merger.apply(srcA[iA++],srcB[iB++]);
                if(nonEmpty.test(merged)) dest[iD++] = merged;
            }
        }
    }
}

