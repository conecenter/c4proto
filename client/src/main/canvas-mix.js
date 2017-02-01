
import * as Canvas from "canvas"

export default function CanvasMix(getCanvasData, setupExtra){
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
    Canvas.CanvasManager(getCanvasData, util, setup).start()
}


// \.\w+\s*=[^=]
