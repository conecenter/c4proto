
export default function CanvasManager(canvasFactory,feedback){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}

    const showCanvasData = (branchKey,data) => state => state.updateBranch(branchKey,{
        branchKey,
        parsed: JSON.parse(data),
        canvas: state.canvas || canvasFactory(),
        checkActivate: state => state.canvas.checkActivate(state)
    })(state)

    const ackCanvasResize = (branchKey,acknowledgedSizes) => state => state.updateBranch(branchKey,{
        acknowledgedSizes
    })(state)

    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branches cleanup
    return ({branchHandlers})
}
