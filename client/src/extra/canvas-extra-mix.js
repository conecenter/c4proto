
import * as Canvas from "../main/canvas"
import * as CanvasExtra from "../extra/canvas-extra"

export default function CanvasExtraMix(log){ // Canvas.ExchangeCanvasSetup(canvas,()=>document.body)
    return options => canvas => [
        options.noOverlay ? Canvas.NoOverlayCanvasSetup(canvas) : CanvasExtra.OverlayCanvasSetup(canvas),
        CanvasExtra.BoundTextCanvasSetup(canvas),
        CanvasExtra.TransitionCanvasSetup(canvas,log)
    ]
}
