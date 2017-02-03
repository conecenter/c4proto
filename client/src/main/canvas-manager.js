
export default function CanvasManager(canvasFactory,feedback){
    const checkActivate = state => {
        state.canvas.checkActivate(state.fromServer)
        return state
    }
    const setup = state => {
        const canvas = state.canvas || canvasFactory()
        const {parsed,branchKey,acknowledgedSizes} = state
        const fromServer = {...parsed,branchKey,acknowledgedSizes}
        return ({...state, fromServer, checkActivate, canvas})
    }
    const showCanvasData =
        data => state => setup({...state, parsed: JSON.parse(data) })
    const ackCanvasResize =
        acknowledgedSizes => state => setup({...state, acknowledgedSizes})
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