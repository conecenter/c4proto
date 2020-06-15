package ee.cone.c4assemble;
public class PartMultiFor {
  public interface Handler1 {
    public void handleMultiForParts(MultiForPart a0);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,Handler1 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  
      handler.handleMultiForParts(i0.head());
    }
  }

  public interface Handler2 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,Handler2 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head());
    }}
  }

  public interface Handler3 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,Handler3 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head());
    }}}
  }

  public interface Handler4 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2, MultiForPart a3);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,scala.collection.Iterable<MultiForPart> a3,Handler4 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  for (scala.collection.Iterable<MultiForPart> i3 = a3; i3.nonEmpty(); i3 = i3.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head(), i3.head());
    }}}}
  }

  public interface Handler5 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2, MultiForPart a3, MultiForPart a4);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,scala.collection.Iterable<MultiForPart> a3,scala.collection.Iterable<MultiForPart> a4,Handler5 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  for (scala.collection.Iterable<MultiForPart> i3 = a3; i3.nonEmpty(); i3 = i3.tail()){  for (scala.collection.Iterable<MultiForPart> i4 = a4; i4.nonEmpty(); i4 = i4.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head(), i3.head(), i4.head());
    }}}}}
  }

  public interface Handler6 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2, MultiForPart a3, MultiForPart a4, MultiForPart a5);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,scala.collection.Iterable<MultiForPart> a3,scala.collection.Iterable<MultiForPart> a4,scala.collection.Iterable<MultiForPart> a5,Handler6 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  for (scala.collection.Iterable<MultiForPart> i3 = a3; i3.nonEmpty(); i3 = i3.tail()){  for (scala.collection.Iterable<MultiForPart> i4 = a4; i4.nonEmpty(); i4 = i4.tail()){  for (scala.collection.Iterable<MultiForPart> i5 = a5; i5.nonEmpty(); i5 = i5.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head(), i3.head(), i4.head(), i5.head());
    }}}}}}
  }

  public interface Handler7 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2, MultiForPart a3, MultiForPart a4, MultiForPart a5, MultiForPart a6);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,scala.collection.Iterable<MultiForPart> a3,scala.collection.Iterable<MultiForPart> a4,scala.collection.Iterable<MultiForPart> a5,scala.collection.Iterable<MultiForPart> a6,Handler7 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  for (scala.collection.Iterable<MultiForPart> i3 = a3; i3.nonEmpty(); i3 = i3.tail()){  for (scala.collection.Iterable<MultiForPart> i4 = a4; i4.nonEmpty(); i4 = i4.tail()){  for (scala.collection.Iterable<MultiForPart> i5 = a5; i5.nonEmpty(); i5 = i5.tail()){  for (scala.collection.Iterable<MultiForPart> i6 = a6; i6.nonEmpty(); i6 = i6.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head(), i3.head(), i4.head(), i5.head(), i6.head());
    }}}}}}}
  }

  public interface Handler8 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2, MultiForPart a3, MultiForPart a4, MultiForPart a5, MultiForPart a6, MultiForPart a7);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,scala.collection.Iterable<MultiForPart> a3,scala.collection.Iterable<MultiForPart> a4,scala.collection.Iterable<MultiForPart> a5,scala.collection.Iterable<MultiForPart> a6,scala.collection.Iterable<MultiForPart> a7,Handler8 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  for (scala.collection.Iterable<MultiForPart> i3 = a3; i3.nonEmpty(); i3 = i3.tail()){  for (scala.collection.Iterable<MultiForPart> i4 = a4; i4.nonEmpty(); i4 = i4.tail()){  for (scala.collection.Iterable<MultiForPart> i5 = a5; i5.nonEmpty(); i5 = i5.tail()){  for (scala.collection.Iterable<MultiForPart> i6 = a6; i6.nonEmpty(); i6 = i6.tail()){  for (scala.collection.Iterable<MultiForPart> i7 = a7; i7.nonEmpty(); i7 = i7.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head(), i3.head(), i4.head(), i5.head(), i6.head(), i7.head());
    }}}}}}}}
  }

  public interface Handler9 {
    public void handleMultiForParts(MultiForPart a0, MultiForPart a1, MultiForPart a2, MultiForPart a3, MultiForPart a4, MultiForPart a5, MultiForPart a6, MultiForPart a7, MultiForPart a8);
  }
  public static void foreach(scala.collection.Iterable<MultiForPart> a0,scala.collection.Iterable<MultiForPart> a1,scala.collection.Iterable<MultiForPart> a2,scala.collection.Iterable<MultiForPart> a3,scala.collection.Iterable<MultiForPart> a4,scala.collection.Iterable<MultiForPart> a5,scala.collection.Iterable<MultiForPart> a6,scala.collection.Iterable<MultiForPart> a7,scala.collection.Iterable<MultiForPart> a8,Handler9 handler){
    for (scala.collection.Iterable<MultiForPart> i0 = a0; i0.nonEmpty(); i0 = i0.tail()){  for (scala.collection.Iterable<MultiForPart> i1 = a1; i1.nonEmpty(); i1 = i1.tail()){  for (scala.collection.Iterable<MultiForPart> i2 = a2; i2.nonEmpty(); i2 = i2.tail()){  for (scala.collection.Iterable<MultiForPart> i3 = a3; i3.nonEmpty(); i3 = i3.tail()){  for (scala.collection.Iterable<MultiForPart> i4 = a4; i4.nonEmpty(); i4 = i4.tail()){  for (scala.collection.Iterable<MultiForPart> i5 = a5; i5.nonEmpty(); i5 = i5.tail()){  for (scala.collection.Iterable<MultiForPart> i6 = a6; i6.nonEmpty(); i6 = i6.tail()){  for (scala.collection.Iterable<MultiForPart> i7 = a7; i7.nonEmpty(); i7 = i7.tail()){  for (scala.collection.Iterable<MultiForPart> i8 = a8; i8.nonEmpty(); i8 = i8.tail()){  
      handler.handleMultiForParts(i0.head(), i1.head(), i2.head(), i3.head(), i4.head(), i5.head(), i6.head(), i7.head(), i8.head());
    }}}}}}}}}
  }
}