
import * as Canvas from "../main/canvas"
import * as CanvasExtra from "../extra/canvas-extra"
import MultiTouchSetup from "../extra/canvas-multitouch"

export default function CanvasExtraMix(log){ // Canvas.ExchangeCanvasSetup(canvas,feedback,()=>document.body)
    return options => canvas => [
        options.noOverlay ? Canvas.NoOverlayCanvasSetup(canvas) : CanvasExtra.OverlayCanvasSetup(canvas),
        CanvasExtra.BoundTextCanvasSetup(canvas),
        CanvasExtra.TransitionCanvasSetup(canvas,log),
        MultiTouchSetup(canvas,log)
    ]
}
