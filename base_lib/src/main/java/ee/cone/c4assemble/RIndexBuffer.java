package ee.cone.c4assemble;

public final class RIndexBuffer <T> {
    public int end = 0;
    private final T[] values;
    public RIndexBuffer(T[] values) {
        this.values = values;
    }
    public void add(T value) {
        values[end++] = value;
    }
    public void add(T[] src, int srcStart, int sz) {
        if(sz==1) add(src[srcStart]);
        else {
            System.arraycopy(src, srcStart, values, end, sz);
            end += sz;
        }
    }
    public T[] result() {
        return java.util.Arrays.copyOf(values, end);
    }
}
