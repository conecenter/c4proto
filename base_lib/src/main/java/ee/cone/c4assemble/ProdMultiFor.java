package ee.cone.c4assemble;
public class ProdMultiFor {
  public interface Handler1 {
    public void handleProducts(scala.Product a0);
  }
  public static void foreach(scala.Product[] a0, Handler1 handler){
    for (int i0 = 0; i0<a0.length; i0++){  
      handler.handleProducts(a0[i0]);
    }
  }

  public interface Handler2 {
    public void handleProducts(scala.Product a0, scala.Product a1);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, Handler2 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  
      handler.handleProducts(a0[i0], a1[i1]);
    }}
  }

  public interface Handler3 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, Handler3 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2]);
    }}}
  }

  public interface Handler4 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2, scala.Product a3);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, scala.Product[] a3, Handler4 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  for (int i3 = 0; i3<a3.length; i3++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2], a3[i3]);
    }}}}
  }

  public interface Handler5 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2, scala.Product a3, scala.Product a4);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, scala.Product[] a3, scala.Product[] a4, Handler5 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  for (int i3 = 0; i3<a3.length; i3++){  for (int i4 = 0; i4<a4.length; i4++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2], a3[i3], a4[i4]);
    }}}}}
  }

  public interface Handler6 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2, scala.Product a3, scala.Product a4, scala.Product a5);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, scala.Product[] a3, scala.Product[] a4, scala.Product[] a5, Handler6 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  for (int i3 = 0; i3<a3.length; i3++){  for (int i4 = 0; i4<a4.length; i4++){  for (int i5 = 0; i5<a5.length; i5++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2], a3[i3], a4[i4], a5[i5]);
    }}}}}}
  }

  public interface Handler7 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2, scala.Product a3, scala.Product a4, scala.Product a5, scala.Product a6);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, scala.Product[] a3, scala.Product[] a4, scala.Product[] a5, scala.Product[] a6, Handler7 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  for (int i3 = 0; i3<a3.length; i3++){  for (int i4 = 0; i4<a4.length; i4++){  for (int i5 = 0; i5<a5.length; i5++){  for (int i6 = 0; i6<a6.length; i6++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2], a3[i3], a4[i4], a5[i5], a6[i6]);
    }}}}}}}
  }

  public interface Handler8 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2, scala.Product a3, scala.Product a4, scala.Product a5, scala.Product a6, scala.Product a7);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, scala.Product[] a3, scala.Product[] a4, scala.Product[] a5, scala.Product[] a6, scala.Product[] a7, Handler8 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  for (int i3 = 0; i3<a3.length; i3++){  for (int i4 = 0; i4<a4.length; i4++){  for (int i5 = 0; i5<a5.length; i5++){  for (int i6 = 0; i6<a6.length; i6++){  for (int i7 = 0; i7<a7.length; i7++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2], a3[i3], a4[i4], a5[i5], a6[i6], a7[i7]);
    }}}}}}}}
  }

  public interface Handler9 {
    public void handleProducts(scala.Product a0, scala.Product a1, scala.Product a2, scala.Product a3, scala.Product a4, scala.Product a5, scala.Product a6, scala.Product a7, scala.Product a8);
  }
  public static void foreach(scala.Product[] a0, scala.Product[] a1, scala.Product[] a2, scala.Product[] a3, scala.Product[] a4, scala.Product[] a5, scala.Product[] a6, scala.Product[] a7, scala.Product[] a8, Handler9 handler){
    for (int i0 = 0; i0<a0.length; i0++){  for (int i1 = 0; i1<a1.length; i1++){  for (int i2 = 0; i2<a2.length; i2++){  for (int i3 = 0; i3<a3.length; i3++){  for (int i4 = 0; i4<a4.length; i4++){  for (int i5 = 0; i5<a5.length; i5++){  for (int i6 = 0; i6<a6.length; i6++){  for (int i7 = 0; i7<a7.length; i7++){  for (int i8 = 0; i8<a8.length; i8++){  
      handler.handleProducts(a0[i0], a1[i1], a2[i2], a3[i3], a4[i4], a5[i5], a6[i6], a7[i7], a8[i8]);
    }}}}}}}}}
  }
}