package ee.cone.c4assemble;

//import jdk.internal.access.JavaLangAccess; String fastUUID(long lsb, long msb);
//import jdk.internal.access.SharedSecrets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

//import static java.lang.String.LATIN1;

public final class C4UUID {
    private final static MessageDigest mdProto = getMessageDigest();
    private static MessageDigest getMessageDigest(){
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("MD5 not supported", e);
        }
    }
    private static MessageDigest getMessageDigestClone(){
        try {
            return (MessageDigest) mdProto.clone();
        } catch (CloneNotSupportedException e){
            throw new InternalError("clone not supported", e);
        }
    }
    public final static class Impl {
        private final MessageDigest md;
        private final byte[] buf;
        private final byte[] res;
        private int pos;
        public Impl(int size){
            md = getMessageDigestClone();
            buf = new byte[36];
            res = new byte[36*size];
            pos = 0;
        }
        public void add(byte[] name) {
            calculate(md,name,buf);
            System.arraycopy(buf,0,res,pos,buf.length);
            pos += buf.length;
        }
        public String result(){
            assert pos == res.length: "pos must be res.length";
            calculate(md,res,buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
    private static void calculate(MessageDigest md, byte[] name, byte[] buf) {
        byte[] md5Bytes = md.digest(name);
        md5Bytes[6]  &= 0x0f;  /* clear version        */
        md5Bytes[6]  |= 0x30;  /* set to version 3     */
        md5Bytes[8]  &= 0x3f;  /* clear variant        */
        md5Bytes[8]  |= 0x80;  /* set to IETF variant  */
        long msb = 0;
        long lsb = 0;
        assert md5Bytes.length == 16 : "data must be 16 bytes in length";
        for (int i=0; i<8; i++)
            msb = (msb << 8) | (md5Bytes[i] & 0xff);
        for (int i=8; i<16; i++)
            lsb = (lsb << 8) | (md5Bytes[i] & 0xff);
        //UUID(msb,lsb)
        formatUnsignedLong0(lsb,        4, buf, 24, 12);
        formatUnsignedLong0(lsb >>> 48, 4, buf, 19, 4);
        formatUnsignedLong0(msb,        4, buf, 14, 4);
        formatUnsignedLong0(msb >>> 16, 4, buf, 9,  4);
        formatUnsignedLong0(msb >>> 32, 4, buf, 0,  8);
        buf[23] = '-';
        buf[18] = '-';
        buf[13] = '-';
        buf[8]  = '-';
    }
    public static String nameUUIDFromBytes(byte[] name) {
        MessageDigest md = getMessageDigestClone();
        final byte[] buf = new byte[36];
        calculate(md,name,buf);
        return new String(buf, StandardCharsets.UTF_8);
    }
    private static void formatUnsignedLong0(long val, int shift, byte[] buf, int offset, int len) {
        int charPos = offset + len;
        int radix = 1 << shift;
        int mask = radix - 1;
        do {
            buf[--charPos] = (byte) digits[((int) val) & mask];
            val >>>= shift;
        } while (charPos > offset);
    }
    private static final char[] digits = {
            '0' , '1' , '2' , '3' , '4' , '5' ,
            '6' , '7' , '8' , '9' , 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f'
    };
}
