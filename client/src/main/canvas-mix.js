
import * as Canvas from "../main/canvas"
import CanvasManager from "../main/canvas-manager"

export default function CanvasMix(log,util,setupExtra){
    const setup = canvas => [
         Canvas.BaseCanvasSetup(log,util,canvas),
         Canvas.ComplexFillCanvasSetup(util,canvas),
         Canvas.InteractiveCanvasSetup(canvas)
    ].concat(setupExtra(canvas))
    const canvasManager = CanvasManager(Canvas.CanvasFactory(util, setup))
    const branchHandlers = canvasManager.branchHandlers
    return ({branchHandlers})
}


// \.\w+\s*=[^=]
