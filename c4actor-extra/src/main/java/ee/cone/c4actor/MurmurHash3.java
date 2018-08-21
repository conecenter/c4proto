package ee.cone.c4actor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MurmurHash3 implements MurmurConstants {

    private long X64_128_C1 = 0x87c37b91114253d5L;

    private long X64_128_C2 = 0x4cf5ad432745937fL;

    private long murmur1 = 0;
    private long murmur2 = 0;

    public MurmurHash3 clone() {
        return new MurmurHash3();
    }

    public void update(byte data) {
        update(new byte[]{data}, 1);
    }

    public void update(final byte[] data, final int length) {
        long h1 = murmur1;
        long h2 = murmur2;

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.remaining() >= 16) {
            long k1 = buffer.getLong();
            long k2 = buffer.getLong();

            h1 ^= mixK1(k1);

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            h2 ^= mixK2(k2);

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        // ----------
        // tail

        // Advance offset to the unprocessed tail of the data.
//		offset += (nblocks << 4); // nblocks * 16;

        buffer.compact();
        buffer.flip();

        final int remaining = buffer.remaining();
        if (remaining > 0) {
            long k1 = 0;
            long k2 = 0;
            switch (buffer.remaining()) {
                case 15:
                    k2 ^= (long) (buffer.get(14) & UNSIGNED_MASK) << 48;

                case 14:
                    k2 ^= (long) (buffer.get(13) & UNSIGNED_MASK) << 40;

                case 13:
                    k2 ^= (long) (buffer.get(12) & UNSIGNED_MASK) << 32;

                case 12:
                    k2 ^= (long) (buffer.get(11) & UNSIGNED_MASK) << 24;

                case 11:
                    k2 ^= (long) (buffer.get(10) & UNSIGNED_MASK) << 16;

                case 10:
                    k2 ^= (long) (buffer.get(9) & UNSIGNED_MASK) << 8;

                case 9:
                    k2 ^= (long) (buffer.get(8) & UNSIGNED_MASK);

                case 8:
                    k1 ^= buffer.getLong();
                    break;

                case 7:
                    k1 ^= (long) (buffer.get(6) & UNSIGNED_MASK) << 48;

                case 6:
                    k1 ^= (long) (buffer.get(5) & UNSIGNED_MASK) << 40;

                case 5:
                    k1 ^= (long) (buffer.get(4) & UNSIGNED_MASK) << 32;

                case 4:
                    k1 ^= (long) (buffer.get(3) & UNSIGNED_MASK) << 24;

                case 3:
                    k1 ^= (long) (buffer.get(2) & UNSIGNED_MASK) << 16;

                case 2:
                    k1 ^= (long) (buffer.get(1) & UNSIGNED_MASK) << 8;

                case 1:
                    k1 ^= (long) (buffer.get(0) & UNSIGNED_MASK);
                    break;

                default:
                    throw new AssertionError("Code should not reach here!");
            }

            // mix
            h1 ^= mixK1(k1);
            h2 ^= mixK2(k2);
        }

        // ----------
        // finalization

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

    public long[] digest() {
        return new long[]{murmur1, murmur2};
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

    /**
     * fmix function for 64 bits.
     *
     * @param k
     * @return
     */
    private long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;

        return k;
    }

}
