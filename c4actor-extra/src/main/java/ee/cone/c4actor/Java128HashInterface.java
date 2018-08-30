package ee.cone.c4actor;


public interface Java128HashInterface {
    void reset();

    String getStringHash();

    long digest1();

    long digest2();

    void updateByte(byte a);

    void updateBoolean(boolean a);

    void updateInt(int a);

    void updateLong(long a);

    void updateString(String a);

    void updateLongs(long[] data, int length);

    void updateBytes(byte[] data, int length);

    void updateBytes(byte[] data);
}
