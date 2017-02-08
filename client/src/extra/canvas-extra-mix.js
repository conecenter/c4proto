
import * as Canvas from "../main/canvas"
import CanvasMix from "../main/canvas-mix"
import * as CanvasExtra from "canvas-extra"

export default function CanvasExtraMix(log,util,setupExtra){ // Canvas.ExchangeCanvasSetup(canvas,feedback,()=>document.body)
    return CanvasMix(log,util,canvas => [
        Canvas.TiledCanvasSetup(canvas), //Canvas.SingleTileCanvasSetup
        Canvas.DragViewPositionCanvasSetup(canvas), //CanvasExtra.ScrollViewPositionCanvasSetup
        CanvasExtra.OverlayCanvasSetup(canvas), //Canvas.NoOverlayCanvasSetup
        CanvasExtra.BoundTextCanvasSetup(canvas),
        CanvasExtra.DragAndDropCanvasSetup(canvas),
        CanvasExtra.TransitionCanvasSetup(canvas)
    ].concat(setupExtra(canvas)))
}
