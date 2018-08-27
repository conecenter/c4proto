package ee.cone.c4actor;



public interface Java128HashInterface {
    public long digest1();
    public long digest2();
    public void updateByte(byte a);
    public void updateBoolean(boolean a);
    public void updateInt(int a);
    public void updateLong(long a);
    public void updateString(String a);
    public void updateLongs(long[] data, int length);
    public void updateBytes(byte[] data, int length);
    public void updateBytes(byte[] data);
}
