package ee.cone.c4assemble;

import java.util.Arrays;

public final class ApproximateInterner {
    private static final int power = Integer.parseInt(nvl(System.getenv("C4INTERN_POWER"), "24"));
    public static final int size = 1 << power;
    private static final int mask = size - 1;
    private static final Object[] table = new Object[size];

    private static String nvl(String a, String b){
        return a == null ? b : a;
    }

    public static <T> T intern(T ref){
        final var h = ref.hashCode();
        final var ix = (h ^ (h >>> 16)) & mask;
        final var was = table[ix];
        if(ref.equals(was)) return (T) was;
        table[ix] = ref;
        return ref;
    }

    public static void clear(){ Arrays.fill(table, null); }

    public static int count(){
        int count = 0;
        for (int i = table.length - 1; i >= 0; i--) if (table[i] != null) count += 1;
        return count;
    }

}
