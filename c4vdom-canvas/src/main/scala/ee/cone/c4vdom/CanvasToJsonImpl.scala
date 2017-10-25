
package ee.cone.c4vdom

import java.awt.geom.{AffineTransform, Point2D}
import java.text.DecimalFormat

object CanvasToJsonImpl extends CanvasToJson {
  def appendJson(attrs: List[PathAttr], transforms: List[Transform], builder: MutableJsonBuilder): Unit =
    PathToJsonImpl(attrs,transforms).buildJson(builder)
  def appendJson(attr: List[CanvasAttr], builder: MutableJsonBuilder): Unit = {
    builder.append("tp").append("Canvas")
    builder.append("ctx").append("ctx")
    builder.append("content").startArray().append("rawMerge").end()

    val decimalFormat = new DecimalFormat("#0.##")
    //val builder = new JsonBuilderImpl()
    builder.append("width").append(1000,decimalFormat) //map size
    builder.append("height").append(1000,decimalFormat)
    builder.append("options");{
      builder.startObject()
      builder.append("noOverlay").append(false)
      builder.end()
    }
    val maxScale = 10
    val zoomSteps = 4096
    val maxZoom = (Math.log(maxScale.toDouble)*zoomSteps).toInt
    builder.append("zoomSteps").append(zoomSteps,decimalFormat)
    builder.append("commandZoom").append(0,decimalFormat)
    builder.append("maxZoom").append(maxZoom,decimalFormat)
  }
}

case class PathToJsonImpl(attrs:List[PathAttr], transforms: List[Transform]) {
  lazy val decimalFormat = new DecimalFormat("#0.##")
  private def appendStyles(builder: MutableJsonBuilder,styles:List[BaseStyleCommand])(sf:BaseStyleCommand => Unit=_=>{}): Unit = {
    styles.foreach(applyStyle(builder)(sf))
    if(styles.exists(_.isInstanceOf[BaseFillStyle])){
      begin(builder);end("fill")(builder)
    }
    if(styles.exists(_.isInstanceOf[BaseStrokeStyle])){
      begin(builder);end("stroke")(builder)
    }
  }
  private def applyStyle(builder: MutableJsonBuilder)=(pf:BaseStyleCommand=>Unit)=>(style:BaseStyleCommand)=>{
    def attrSet(key: String, value: String) = {
      begin(builder);add(key)(builder);add(value)(builder);end("set")(builder)
    }
    style match {
      case FillStyle(v)=>  attrSet("fillStyle",v)
      case StrokeStyle(v)=> attrSet("strokeStyle",v)
      case FontStyle(font, _, fontWeight) ⇒
        val defSize = 20
        attrSet("font",s"${if(fontWeight.nonEmpty) fontWeight else "normal"} ${defSize}px $font")
      case TextAnchorStyle(v) ⇒ attrSet("textAlign",v)
      case TextBaseline(v) ⇒ attrSet("textBaseline",v)
      case DominantBaselineCentralStyle(v) ⇒
        attrSet("textBaseline",if(v) "middle" else "bottom")
      case StrokeWidthStyle(_) =>
      case SetLineDash(_) =>
      case LineCapStyle(v) => attrSet("lineCap",v)
      case LineJoinStyle(v) => attrSet("lineJoin",v)
      case p ⇒ println(s"!non-text style: $p")
    }
    pf(style)
  }
  private def begin(builder: MutableJsonBuilder):Unit = builder.startArray()
  private def end(builder: MutableJsonBuilder):Unit = builder.end()
  private def end(cmd:String)(builder: MutableJsonBuilder):Unit = {builder.end();builder.append(cmd)}
  private def add(v: Boolean)(builder: MutableJsonBuilder):Unit = builder.append(v)
  private def add(v: Double)(builder: MutableJsonBuilder):Unit = builder.append(v, decimalFormat)
  private def add(v: String)(builder: MutableJsonBuilder):Unit = builder.append(v)
  private def transformPoint(x:BigDecimal,y:BigDecimal)(affineTransform: AffineTransform):(Double,Double) = {
    val point = new Point2D.Double
    point.setLocation(x.toDouble,y.toDouble)
    affineTransform.transform(point,point)
    (point.getX,point.getY)
  }
  private def addSetTransform(builder: MutableJsonBuilder,tr: AffineTransform):Unit = {
    begin(builder)
    add(tr.getScaleX)(builder); add(tr.getShearY)(builder); add(tr.getShearX)(builder)
    add(tr.getScaleY)(builder); add(tr.getTranslateX)(builder); add(tr.getTranslateY)(builder)
    end("transform")(builder)
  }
  private def transformSize(v: Double)(affineTransform: AffineTransform):Double = {
    val point = new Point2D.Double
    point.setLocation(v, 0)
    affineTransform.deltaTransform(point, point)
    point.distance(0,0)
  }
  private def addPoints(x:BigDecimal,y:BigDecimal)(builder: MutableJsonBuilder,affineTransform: AffineTransform) = {
    val (_x,_y) = transformPoint(x,y)(affineTransform)
    add(_x)(builder);add(_y)(builder)
  }
  private def startContext(name: String)(builder: MutableJsonBuilder) = {
    builder.startArray()
    builder.append(name)
    builder.startArray()
  }

  private def endContext(name:String = "inContext")(builder: MutableJsonBuilder) = {
    builder.end()
    builder.end()
    builder.append(name)
  }
  lazy val styles: List[BaseStyleCommand] = attrs.collect{case s:BaseStyleCommand=>s}
  lazy val shapes: List[Shape] = attrs.collect{case s:Shape=>s}
  lazy val handlers:List[AbstractCanvasEventHandler] = attrs.collect{case h:AbstractCanvasEventHandler=>h}
  def buildJson(builder: MutableJsonBuilder):Unit={
    val affineTransform = new AffineTransform()
    transforms.reverse.foreach{
      case Scale(v) =>affineTransform.scale(v.toDouble,v.toDouble)
      case Translate(x,y)=> affineTransform.translate(x.toDouble,y.toDouble)
      case Rotate(t) => affineTransform.rotate(t.toDouble)
    }
    builder.startObject()
    builder.append("ctx").append("ctx")
    builder.append("commands"); {
      builder.startArray();
      {
        begin(builder); add("applyPath")(builder); begin(builder)
        val (initStyles,restStyles) = styles.partition{
          case _:StrokeWidthStyle=>true
          case _:LineCapStyle =>true
          case _:LineJoinStyle =>true
          case _:SetLineDash =>true
          case _=>false
        }
        appendStyles(builder,initStyles){
          case StrokeWidthStyle(v) =>
            begin(builder);add("lineWidth")(builder);add(transformSize(v.toDouble)(affineTransform))(builder);end("set")(builder)
          case SetLineDash(dash)=>
            begin(builder); begin(builder)
            dash.split(",").foreach(s⇒add(transformSize(s.trim.toDouble)(affineTransform))(builder))
            end(builder); end("setLineDash")(builder)
        }
        shapes.collect{case s:PathShape=>s}.foreach{
          case Rect(x, y, w, h) =>
            begin(builder);addPoints(x,y)(builder,affineTransform);end("moveTo")(builder)
            begin(builder);addPoints(x+w,y)(builder,affineTransform);end("lineTo")(builder)
            begin(builder);addPoints(x+w,y+h)(builder,affineTransform);end("lineTo")(builder)
            begin(builder);addPoints(x,y+h)(builder,affineTransform);end("lineTo")(builder)
            begin(builder);addPoints(x,y)(builder,affineTransform);end("lineTo")(builder)
          case Line(x,y,toX,toY) =>
            begin(builder);addPoints(x,y)(builder,affineTransform);end("moveTo")(builder)
            begin(builder);addPoints(toX,toY)(builder,affineTransform);end("lineTo")(builder)
          case BezierCurveTo(sdx,sdy,edx,edy,endPointX,endPointY) =>
            begin(builder)
            add(sdx.toDouble)(builder); add(sdy.toDouble)(builder)
            add(edx.toDouble)(builder); add(edy.toDouble)(builder)
            add(endPointX.toDouble)(builder); add(endPointY.toDouble)(builder)
            end("bezierCurveTo")(builder)
          case Ellipse(x,y,rx,ry,rotate,startAngle,endAngle,counterclockwise) =>
            begin(builder); end("save")(builder)
            val tr = new AffineTransform(affineTransform)
            tr.translate(x.toDouble,y.toDouble)
            tr.rotate(rotate.toDouble)
            tr.scale(rx.toDouble,ry.toDouble)
            addSetTransform(builder,tr)
            begin(builder)
            add(0)(builder); add(0)(builder); add(1)(builder)
            add(startAngle.toDouble)(builder); add(endAngle.toDouble)(builder); add(counterclockwise)(builder)
            end("arc")(builder)
            begin(builder); end("restore")(builder)
        }
        end(builder);end("definePath")(builder)
        startContext("preparingCtx")(builder);
        {
          begin(builder);end("beginPath")(builder)
          begin(builder);end("applyPath")(builder)
          appendStyles(builder,restStyles)()
          shapes.collect{case s:NonPathShape=>s}.foreach{
            case Image(url,_,_,canvasWidth,canvasHeight)=>
              begin(builder)
              add("overlayCtx")(builder);add(url)(builder)
              addPoints(0,0)(builder,affineTransform)
              add(transformSize(canvasWidth.toDouble)(affineTransform))(builder)
              add(transformSize(canvasHeight.toDouble)(affineTransform))(builder)
              end("image")(builder)
            case Text(tStyles,text,x,y) =>
              appendStyles(builder,tStyles){case FontStyle(_,fontSize,_)=>
                val defSize = 20
                val sc = fontSize.toDouble / defSize
                val tr = new AffineTransform(affineTransform)
                tr.translate(x.toDouble,y.toDouble)
                tr.scale(sc,sc)
                addSetTransform(builder,tr)
              }
              begin(builder); add(text)(builder); add(0)(builder); add(0)(builder); end("fillText")(builder)

          }
        };
        endContext()(builder)
        if(handlers.nonEmpty) {
          val evColor = "[colorPH]"
          begin(builder); add(evColor)(builder);begin(builder)
          startContext("overlayCtx")(builder);
          {
            val bgColor = "rgba(255,255,255,0.45)"
            begin(builder);end("beginPath")(builder)
            begin(builder);end("applyPath")(builder)
            appendStyles(builder, List(FillStyle(bgColor), StrokeStyle(bgColor)))()
          };
          endContext()(builder)
          end(builder);
          end("over")(builder);
          startContext("reactiveCtx")(builder);
          {
            begin(builder);
            end("beginPath")(builder)
            begin(builder);
            end("applyPath")(builder)
            appendStyles(builder, List(FillStyle(evColor), StrokeStyle(evColor)))()
          };
          endContext()(builder)
        }
      }
      builder.end()
    }
    builder.end()
  }
}