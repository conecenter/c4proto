
export default function CanvasManager(canvasFactory,feedback){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}

    const updateBranch = (branchKey,data) => transformNested("branches",transformNested(branchKey,
        v=>({...v,...data})
    ))

    const showCanvasData = (branchKey,data) => state => state.updateBranch(branchKey,{
        branchKey,
        parsed: JSON.parse(data),
        canvas: state.canvas || canvasFactory(),
        checkActivate: state => state.branches[branchKey].canvas.checkActivate(state)
    })(state)

    const ackCanvasResize = (branchKey,acknowledgedSizes) => updateBranch(branchKey,{
        acknowledgedSizes
    })

    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branches cleanup
    return ({branchHandlers})
}
