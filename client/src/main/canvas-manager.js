
export default function CanvasManager(canvasFactory,feedback){
    const checkActivate = state => {
        state.canvas.checkActivate(state.fromServer)
        return state
    }
    const send = req => feedback.send("/connection", {...req, "X-r-branch": state.branchKey})
    const reportSizes =
        (canvasFontSize, canvasWidth) => canvasFontSize+" "+canvasWidth === ???state!!.acknowledgedSizes ||
        send({
            "X-r-canvas-eventType": "canvasResize",
            "X-r-canvas-canvasFontSize": canvasFontSize,
            "X-r-canvas-canvasWidth": canvasWidth
        })
    const onZoom = ()=>() //todo to close popup?
    const showCanvasData = data => existingState => {
        const state = existingState.canvas ?
            existingState : {...existingState, canvas: canvasFactory(), checkActivate}
        const parsed = JSON.parse(data)
        const scrollNode = document.body //todo to limit?
        const fromServer = {...parsed, send, scrollNode}
        return {...state, fromServer}
    }
    const ackCanvasResize = acknowledgedSizes => state => ({...state, acknowledgedSizes})
    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branches cleanup
    return ({branchHandlers})
}

/*
from server:
    width height
    commands
    zoomSteps
    commandZoom
    maxZoom
*/