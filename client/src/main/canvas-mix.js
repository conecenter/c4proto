
import * as Canvas from "../main/canvas"
import CanvasManager from "../main/canvas-manager"

export default function CanvasMix(util,setupExtra){
    const setup = canvas => [
         Canvas.BaseCanvasSetup(util,canvas),
         Canvas.ComplexFillCanvasSetup(util,canvas),
         Canvas.InteractiveCanvasSetup(canvas)
    ].concat(setupExtra(canvas))
    const canvasManager = CanvasManager(Canvas.CanvasFactory(util, setup))
    const branchHandlers = canvasManager.branchHandlers
    return ({branchHandlers})
}


// \.\w+\s*=[^=]
