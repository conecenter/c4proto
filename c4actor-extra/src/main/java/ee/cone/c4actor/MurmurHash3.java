package ee.cone.c4actor;

public class MurmurHash3 implements MurmurConstants, Java128HashInterface {

    private long X64_128_C1 = 0x87c37b91114253d5L;

    private long X64_128_C2 = 0x4cf5ad432745937fL;

    private long murmur1 = 0;
    private long murmur2 = 0;

    public MurmurHash3 clone() {
        return new MurmurHash3();
    }

    private long getLong(byte[] data, int offset) {
        return (long) (data[offset + 6] & UNSIGNED_MASK) << 56
                | (long) (data[offset + 6] & UNSIGNED_MASK) << 48
                | (long) (data[offset + 5] & UNSIGNED_MASK) << 40
                | (long) (data[offset + 4] & UNSIGNED_MASK) << 32
                | (long) (data[offset + 3] & UNSIGNED_MASK) << 24
                | (long) (data[offset + 2] & UNSIGNED_MASK) << 16
                | (long) (data[offset + 1] & UNSIGNED_MASK) << 8
                | (long) (data[offset] & UNSIGNED_MASK);
    }

    @Override
    public long digest1() {
        return murmur1;
    }

    @Override
    public long digest2() {
        return murmur2;
    }

    @Override
    public void updateByte(byte a) {
        long h1 = murmur1;
        long h2 = murmur2;
        long k1 = (long) (a & UNSIGNED_MASK);
        long k2 = 0;

        h1 ^= mixK1(k1);
        h2 ^= mixK2(k2);
        h1 ^= 1;
        h2 ^= 1;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        murmur1 = h1;
        murmur2 = h2;
    }

    @Override
    public void updateBoolean(boolean a) {
        updateByte((byte) (a ? 0x1 : 0x0));
    }

    @Override
    public void updateInt(int a) {
        long h1 = murmur1;
        long h2 = murmur2;
        long k1 = (long) a;
        long k2 = 0;

        h1 ^= mixK1(k1);
        h2 ^= mixK2(k2);
        h1 ^= 4;
        h2 ^= 4;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        murmur1 = h1;
        murmur2 = h2;
    }

    @Override
    public void updateLong(long a) {
        long h1 = murmur1;
        long h2 = murmur2;
        long k2 = 0;

        h1 ^= mixK1(a);
        h2 ^= mixK2(k2);
        h1 ^= 8;
        h2 ^= 8;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        murmur1 = h1;
        murmur2 = h2;
    }

    @Override
    public void updateString(String a) {
        final int len = a.length();

        final int nblocks = len / 8;

        long h1 = murmur1;
        long h2 = murmur2;

        for (int i = 0; i < nblocks; ++i) {
            int i0 = (i * 2) * 4;
            int i1 = (i * 2 + 1) * 4;

            long k1 = (long) a.charAt(i0)
                    | (long) a.charAt(i0 + 1) << 16
                    | (long) a.charAt(i0 + 2) << 32
                    | (long) a.charAt(i0 + 3) << 48;
            long k2 = (long) a.charAt(i1)
                    | (long) a.charAt(i1 + 1) << 16
                    | (long) a.charAt(i1 + 2) << 32
                    | (long) a.charAt(i1 + 3) << 48;

            k1 *= X64_128_C1;
            k1 = k1 << 31 | k1 >>> (64 - 31);
            k1 *= X64_128_C2;
            h1 ^= k1;

            h1 = h1 << 27 | h1 >>> (64 - 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= X64_128_C2;
            k2 = k2 << 33 | k2 >>> (64 - 33);
            k2 *= X64_128_C1;
            h2 ^= k2;

            h2 = h2 << 31 | h1 >>> (64 - 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;

        switch (len & 7) {
            case 7:
                k2 ^= (long) a.charAt(6) << 32;
            case 6:
                k2 ^= (long) a.charAt(5) << 16;
            case 5:
                k2 ^= (long) a.charAt(4);
                k2 *= X64_128_C1;
                k2 = k2 << 33 | k2 >>> (64 - 33);
                k2 *= X64_128_C1;
                h2 ^= k2;

            case 4:
                k1 ^= (long) a.charAt(3) << 48;
            case 3:
                k1 ^= (long) a.charAt(2) << 32;
            case 2:
                k1 ^= (long) a.charAt(1) << 16;
            case 1:
                k1 ^= (long) a.charAt(0);
                k1 *= X64_128_C1;
                k1 = k1 << 31 | k1 >>> (64 - 31);
                k1 *= X64_128_C2;
                h1 ^= k1;
            case 0:
                break;
        }

        h1 ^= len;
        h2 ^= len;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        murmur1 = h1;
        murmur2 = h2;
    }

    @Override
    public void updateLongs(long[] data, int length) {
        int current = 0;
        int remaining = length;

        long h1 = murmur1;
        long h2 = murmur2;

        while (remaining >= 2) {
            remaining -= 2;
            current += 1;
            long k1 = data[current];
            current += 1;
            long k2 = data[current];

            h1 ^= mixK1(k1);

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            h2 ^= mixK2(k2);

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        if (remaining > 0) {
            long k1 = data[current];
            long k2 = 0;

            h1 ^= mixK1(k1);
            h2 ^= mixK2(k2);
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        murmur1 = h1;
        murmur2 = h2;
    }

    public void updateBytes(byte[] data) {
        updateBytes(data, data.length);
    }

    public void updateBytes(final byte[] data, final int length) {
        int current = 0;
        int remaining = length;

        long h1 = murmur1;
        long h2 = murmur2;

        while (remaining >= 16) {
            remaining -= 16;
            current += 8;
            long k1 = getLong(data, current);
            current += 8;
            long k2 = getLong(data, current);

            h1 ^= mixK1(k1);

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            h2 ^= mixK2(k2);

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        if (remaining > 0) {
            long k1 = 0;
            long k2 = 0;
            switch (remaining) {
                case 15:
                    k2 ^= (long) (data[current + 14] & UNSIGNED_MASK) << 48;

                case 14:
                    k2 ^= (long) (data[current + 13] & UNSIGNED_MASK) << 40;

                case 13:
                    k2 ^= (long) (data[current + 12] & UNSIGNED_MASK) << 32;

                case 12:
                    k2 ^= (long) (data[current + 11] & UNSIGNED_MASK) << 24;

                case 11:
                    k2 ^= (long) (data[current + 10] & UNSIGNED_MASK) << 16;

                case 10:
                    k2 ^= (long) (data[current + 9] & UNSIGNED_MASK) << 8;

                case 9:
                    k2 ^= (long) (data[current + 8] & UNSIGNED_MASK);

                case 8:
                    k1 ^= getLong(data, current);
                    break;

                case 7:
                    k1 ^= (long) (data[current + 6] & UNSIGNED_MASK) << 48;

                case 6:
                    k1 ^= (long) (data[current + 5] & UNSIGNED_MASK) << 40;

                case 5:
                    k1 ^= (long) (data[current + 4] & UNSIGNED_MASK) << 32;

                case 4:
                    k1 ^= (long) (data[current + 3] & UNSIGNED_MASK) << 24;

                case 3:
                    k1 ^= (long) (data[current + 2] & UNSIGNED_MASK) << 16;

                case 2:
                    k1 ^= (long) (data[current + 1] & UNSIGNED_MASK) << 8;

                case 1:
                    k1 ^= (long) (data[current] & UNSIGNED_MASK);
                    break;

                default:
                    throw new AssertionError("Code should not reach here!");
            }

            h1 ^= mixK1(k1);
            h2 ^= mixK2(k2);
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        murmur1 = h1;
        murmur2 = h2;
    }

    public void reset() {
        murmur1 = 0;
        murmur2 = 0;
    }

    private long mixK1(long k1) {
        k1 *= X64_128_C1;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= X64_128_C2;

        return k1;
    }

    private long mixK2(long k2) {
        k2 *= X64_128_C2;
        k2 = Long.rotateLeft(k2, 33);
        k2 *= X64_128_C1;

        return k2;
    }

    private long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;

        return k;
    }

}