
import * as Canvas from "../main/canvas"


export function CanvasBaseMix(log,util){
    return canvas => [
         Canvas.BaseCanvasSetup(log,util,canvas),
         Canvas.ComplexFillCanvasSetup(util,canvas),
         Canvas.InteractiveCanvasSetup(canvas)
    ]
}

export function CanvasSimpleMix(){
    return canvas => [
        Canvas.TiledCanvasSetup(canvas),
        Canvas.DragViewPositionCanvasSetup(canvas),
        Canvas.NoOverlayCanvasSetup(canvas)
    ]
}

// \.\w+\s*=[^=]
