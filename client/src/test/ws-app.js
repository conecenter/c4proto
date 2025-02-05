
import {CanvasUtil,ExchangeCanvasSetup,CanvasFactory} from "../extra/canvas"
import {CanvasBaseMix,CanvasSimpleMix} from "../extra/canvas-mix"
import {main} from "./ws-app.tsx"

const log = v => console.log(v)
const util = CanvasUtil()
const exchangeMix = options => canvas => ExchangeCanvasSetup(canvas)
const canvasMods = [CanvasBaseMix(log,util),CanvasSimpleMix(),exchangeMix]
const canvasFactory = CanvasFactory(util, canvasMods)

main({win: window, canvasFactory})
