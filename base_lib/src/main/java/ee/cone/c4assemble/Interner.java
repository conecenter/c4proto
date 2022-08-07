package ee.cone.c4assemble;

public class Interner {
    static boolean enabled = false;
    public static String intern(String s) {
        return enabled ? s.intern() : s;
    }
}
