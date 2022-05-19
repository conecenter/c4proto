package ee.cone.c4assemble;

//import jdk.internal.access.JavaLangAccess; String fastUUID(long lsb, long msb);
//import jdk.internal.access.SharedSecrets;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public class C4UUID {
    private final static MessageDigest mdProto = getMessageDigest();
    private static MessageDigest getMessageDigest(){
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("MD5 not supported", e);
        }
    }
    public static UUID nameUUIDFromBytes(byte[] name) {
        try {
            MessageDigest md = (MessageDigest) mdProto.clone();
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
            return new UUID(msb,lsb);
        } catch (CloneNotSupportedException e){
            throw new InternalError("clone not supported", e);
        }
    }
}
