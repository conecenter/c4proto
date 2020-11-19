
import * as Canvas from "../main/canvas"


export function CanvasBaseMix(log,util){
    return options => canvas => [
         Canvas.BaseCanvasSetup(log,util,canvas),
         Canvas.ComplexFillCanvasSetup(util,canvas),
         Canvas.InteractiveCanvasSetup(canvas),
         options.singleTile ?
             Canvas.SingleTileCanvasSetup(canvas) :
             Canvas.TiledCanvasSetup(canvas),
         options.disableDragAndZoom ?
             Canvas.ScrollViewPositionCanvasSetup(canvas) :
             Canvas.DragViewPositionCanvasSetup(log,canvas),
         Canvas.ResizeCanvasSetup(canvas),
         Canvas.MouseCanvasSetup(log,canvas)
    ]
}

export function CanvasSimpleMix(){
    return options => canvas => [
        Canvas.NoOverlayCanvasSetup(canvas)
    ]
}

// \.\w+\s*=[^=]
