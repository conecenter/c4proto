
import * as Canvas from "../main/canvas"
import * as CanvasExtra from "../extra/canvas-extra"

export default function CanvasExtraMix(log){ // Canvas.ExchangeCanvasSetup(canvas,feedback,()=>document.body)
    return canvas => [
        Canvas.TiledCanvasSetup(canvas), //Canvas.SingleTileCanvasSetup
        Canvas.DragViewPositionCanvasSetup(canvas), //CanvasExtra.ScrollViewPositionCanvasSetup
        CanvasExtra.OverlayCanvasSetup(canvas), //Canvas.NoOverlayCanvasSetup
        CanvasExtra.BoundTextCanvasSetup(canvas),
        CanvasExtra.TransitionCanvasSetup(canvas,log)
    ]
}
