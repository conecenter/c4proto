
import {CanvasUtil,ExchangeCanvasSetup,CanvasFactory} from "../main/canvas"
import {CanvasBaseMix} from "../main/canvas-mix"
import {main} from "./ws-app.tsx"

const log = v => console.log(v)
const util = CanvasUtil()
const exchangeMix = options => canvas => ExchangeCanvasSetup(canvas)
const canvasMods = [CanvasBaseMix(log,util),exchangeMix]
const canvasFactory = CanvasFactory(util, canvasMods)

main({win: window, canvasFactory})
