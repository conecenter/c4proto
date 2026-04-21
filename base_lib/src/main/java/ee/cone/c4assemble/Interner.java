package ee.cone.c4assemble;

public class Interner {
    static final boolean enabled = isBlank(System.getenv("C4NO_INTERN"));
    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
    public static String intern(String s) {
        return enabled ? s.intern() : s;
    }
}
