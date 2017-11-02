
package ee.cone.c4vdom

import java.text.DecimalFormat

object CanvasToJsonImpl extends CanvasToJson {
  def appendPathJson(attrs: List[PathAttr], builder: MutableJsonBuilder): Unit =
    PathToJsonImpl(attrs)(builder,new DecimalFormat("#0.##")).buildJson()
  def appendCanvasJson(attr: List[CanvasAttr], builder: MutableJsonBuilder): Unit = {
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

case class PathToJsonImpl(attrs:List[PathAttr])(builder: MutableJsonBuilder, decimalFormat: DecimalFormat) {
  private def appendStyles(styles:List[BaseStyleCommand])(sf:BaseStyleCommand => Unit=_=>{}): Unit = {
    styles.foreach(applyStyle(sf))
    if(styles.exists(_.isInstanceOf[BaseFillStyle])) cmd("fill")
    if(styles.exists(_.isInstanceOf[BaseStrokeStyle])) cmd("stroke")
  }
  private def applyStyle = (pf:BaseStyleCommand=>Unit)=>(style:BaseStyleCommand)=>{
    def attrSet(key: String, value: String) = {
      begin();add(key);add(value);end("set")
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
  private def begin():Unit = builder.startArray()
  private def end():Unit = builder.end()
  private def end(cmd:String):Unit = {builder.end();builder.append(cmd)}
  private def add(v: Boolean):Unit = builder.append(v)
  private def add(v: BigDecimal):Unit = builder.append(v, decimalFormat)
  private def add(v: String):Unit = builder.append(v)
  private def add(x:BigDecimal,y:BigDecimal): Unit = { add(x); add(y) }
  private def cmd(v: String): Unit = { begin(); end(v) }
  private def cmd(n: BigDecimal, v: String): Unit = { begin(); add(n); end(v) }
  private def cmd(x: BigDecimal, y: BigDecimal, v: String): Unit = {
    begin(); add(x); add(y); end(v)
  }
  private def startContext(name: String): Unit = { begin(); add(name); begin() }
  private def endContext(name:String = "inContext"): Unit = { end(); end(name) }
  def buildJson():Unit={
    val styles = attrs.collect{case s:BaseStyleCommand=>s}
    val shapes: List[Shape] = attrs.collect{case s:Shape=>s}
    val transforms = attrs.collect{case s:Transform=>s}
    val handlers = attrs.collect{case h:AbstractCanvasEventHandler=>h}
    builder.startObject()
    builder.append("ctx").append("ctx")
    if(transforms.nonEmpty){
      builder.append("commandsFinally"); {
        builder.startArray()
        cmd("setMainContext")
        cmd("restore")
        builder.end()
      }
    }
    builder.append("commands"); {
      builder.startArray();
      {
        if(transforms.nonEmpty){
          cmd("setMainContext")
          cmd("save")
          transforms.reverse.foreach{
            case Scale(v) => cmd(v,v,"scale")
            case Translate(x,y)=> cmd(x,y,"translate")
            case Rotate(t) => cmd(t,"rotate")
          }
        }
        begin(); add("applyPath"); begin()
        val (initStyles,restStyles) = styles.partition{
          case _:StrokeWidthStyle=>true
          case _:LineCapStyle =>true
          case _:LineJoinStyle =>true
          case _:SetLineDash =>true
          case _=>false
        }
        appendStyles(initStyles){
          case StrokeWidthStyle(v) =>
            begin();add("lineWidth");add(v);end("set")
          case SetLineDash(dash)=>
            begin(); begin()
            dash.split(",").foreach(s⇒add(BigDecimal(s.trim)))
            end(); end("setLineDash")
        }
        shapes.collect{case s:PathShape=>s}.foreach{
          case Rect(x, y, w, h) =>
            cmd(x,y,"moveTo")
            cmd(x+w,y,"lineTo")
            cmd(x+w,y+h,"lineTo")
            cmd(x,y+h,"lineTo")
            cmd(x,y,"lineTo")
          case Line(x,y,toX,toY) =>
            cmd(x,y,"moveTo")
            cmd(toX,toY,"lineTo")
          case BezierCurveTo(sdx,sdy,edx,edy,endPointX,endPointY) =>
            begin();
            add(sdx,sdy);add(edx,edy);add(endPointX,endPointY)
            end("bezierCurveTo")
          case Ellipse(x,y,rx,ry,rotate,startAngle,endAngle,counterclockwise) =>
            cmd("save")
            cmd(x,y,"translate")
            cmd(rotate,"rotate")
            cmd(rx,ry,"scale")
            begin()
            add(0,0); add(1); add(startAngle,endAngle); add(counterclockwise)
            end("arc")
            cmd("restore")
        }
        end();end("definePath")
        startContext("preparingCtx");
        {
          cmd("beginPath")
          cmd("applyPath")
          appendStyles(restStyles)()
          shapes.collect{case s:NonPathShape=>s}.foreach{
            case Image(url,_,_,canvasWidth,canvasHeight)=>
              begin()
              add("overlayCtx");add(url);add(0,0);add(canvasWidth,canvasHeight)
              end("image")
            case Text(tStyles,text,x,y) =>
              cmd("save")
              appendStyles(tStyles){case FontStyle(_,fontSize,_)=>
                val defSize = 20
                val sc = fontSize / defSize
                cmd(x,y,"translate")
                cmd(sc,sc,"scale")
              }
              begin(); add(text); add(0,0); end("fillText")
              cmd("restore")
          }
        };
        endContext()
        if(handlers.nonEmpty) {
          val evColor = "[colorPH]"
          begin(); add(evColor); begin()
          startContext("overlayCtx");
          {
            val bgColor = "rgba(255,255,255,0.45)"
            cmd("beginPath")
            cmd("applyPath")
            appendStyles(List(FillStyle(bgColor), StrokeStyle(bgColor)))()
          };
          endContext()
          end(); end("over")
          startContext("reactiveCtx");
          {
            cmd("beginPath")
            cmd("applyPath")
            appendStyles(List(FillStyle(evColor), StrokeStyle(evColor)))()
          };
          endContext()
        }
      }
      builder.end()
    }
    builder.end()
  }
}