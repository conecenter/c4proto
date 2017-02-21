
import * as Canvas from "../main/canvas"
import {chain,addSend}    from "../main/util"


export function CanvasBaseMix(log,util){
    return canvas => [
         Canvas.BaseCanvasSetup(log,util,canvas,chain,addSend),
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
