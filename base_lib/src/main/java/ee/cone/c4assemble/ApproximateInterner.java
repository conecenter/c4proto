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

    // we can not intern any object safely; Some(Long.box(66)) == Some(BigDecimal(66)), Vector==List
    @SuppressWarnings("unchecked")
    public static <T> T intern(T ref){
        final var h = ref.hashCode();
        final var ix = (h ^ (h >>> 16)) & mask;
        final var was = table[ix];
        if(ref.equals(was)) return (T) was;
        table[ix] = ref;
        return ref;
    }
    /* double probe, check with more filled table
    public static <T> T intern(T ref){
        final var h = ref.hashCode();
        final var ix = (h ^ (h >>> 16)) & mask;
        final var was = table[ix];
        if(ref.equals(was)) return (T) was;
        final var ix2 = (ix + 1) & mask;
        final var was2 = table[ix2];
        if (ref.equals(was2)) return (T) was2;
        table[was == null ? ix : was2 == null ? ix2 : ix] = ref;
        return ref;
    }*/


    public static void clear(){ Arrays.fill(table, null); }

    public static int count(){
        int count = 0;
        for (int i = table.length - 1; i >= 0; i--) if (table[i] != null) count += 1;
        return count;
    }
}
