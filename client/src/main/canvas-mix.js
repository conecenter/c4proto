
import * as Canvas from "../main/canvas"
import CanvasManager from "../main/canvas-manager"

export default function CanvasMix(setupExtra){
    const util = Canvas.CanvasUtil()
    const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util)
    const mouseCanvasSystem = Canvas.MouseCanvasSystem(util)
    const setup = canvas => [
         Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem),
         Canvas.BaseCanvasSetup(util,canvas),
         Canvas.ComplexFillCanvasSetup(util,canvas),
         Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
         Canvas.InteractiveCanvasSetup(canvas)
    ].concat(setupExtra(canvas))
    const canvasManager = Canvas.CanvasManager(Canvas.CanvasFactory(util, setup))
    const branchHandlers = canvasManager.branchHandlers
    return ({branchHandlers})
}


// \.\w+\s*=[^=]
