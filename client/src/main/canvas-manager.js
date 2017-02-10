
export default function CanvasManager(canvasFactory,feedback){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}

    const showCanvasData = data => state => ({
        ...state,
        parsed: JSON.parse(data),
        canvas: state.canvas || canvasFactory(),
        checkActivate: state => state.canvas.checkActivate(state)
    })

    const ackCanvasResize =
        acknowledgedSizes => state => ({...state, acknowledgedSizes})
    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branches cleanup
    return ({branchHandlers})
}
