
import * as Canvas from "../main/canvas"
import * as CanvasExtra from "../extra/canvas-extra"

export default function CanvasExtraMix(log){ // Canvas.ExchangeCanvasSetup(canvas,()=>document.body)
    return options => canvas => [
        options.singleTile ? Canvas.SingleTileCanvasSetup(canvas) : Canvas.TiledCanvasSetup(canvas),
        options.disableDragAndZoom ? (
            options.maxViewHeight ?
                CanvasExtra.ScrollViewPositionMaxHeightCanvasSetup(canvas):
                CanvasExtra.ScrollViewPositionCanvasSetup(canvas)
        ) : Canvas.DragViewPositionCanvasSetup(canvas),
        options.noOverlay ? Canvas.NoOverlayCanvasSetup(canvas) : CanvasExtra.OverlayCanvasSetup(canvas),
        CanvasExtra.BoundTextCanvasSetup(canvas),
        CanvasExtra.TransitionCanvasSetup(canvas,log)
    ]
}
