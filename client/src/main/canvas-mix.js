
import * as Canvas from "../main/canvas"


export function CanvasBaseMix(log,util,system){
    return options => canvas => [
         Canvas.BaseCanvasSetup(log,util,canvas,system),
         Canvas.ComplexFillCanvasSetup(util,canvas),
         Canvas.InteractiveCanvasSetup(canvas),
         options.singleTile ?
             Canvas.SingleTileCanvasSetup(canvas) :
             Canvas.TiledCanvasSetup(canvas),
         options.disableDragAndZoom ?
             Canvas.ScrollViewPositionCanvasSetup(canvas) :
             Canvas.DragViewPositionCanvasSetup(canvas)
    ]
}

export function CanvasSimpleMix(){
    return options => canvas => [
        Canvas.NoOverlayCanvasSetup(canvas)
    ]
}

// \.\w+\s*=[^=]
