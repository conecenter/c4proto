
export default function CanvasManager(canvasFactory,feedback){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}

    const showCanvasData = data => state => {
        const canvas = state.canvas || canvasFactory()
        return ({
            ...state, canvas,
            remove: canvas.remove, checkActivate: canvas.checkActivate,
            parsed: JSON.parse(data)
        })
    }

    const ackCanvasResize =
        acknowledgedSizes => state => ({...state, acknowledgedSizes})
    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branches cleanup
    return ({branchHandlers})
}
