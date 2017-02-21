
export default function CanvasManager(canvasFactory,transformNested,chain){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}

    const updateBranch = (branchKey,transform) => transformNested("branches",transformNested(branchKey,
        transform
    ))

    const showCanvasData = (branchKey,data) => updateBranch(branchKey,was=>({
        parsed: JSON.parse(data),
        canvas: was.canvas || canvasFactory()
    }))

    const ackCanvasResize = (branchKey,acknowledgedSizes) => updateBranch(branchKey,was=>({
        ...was, acknowledgedSizes
    }))

    const checkActivate = state => chain(
        Object.values(state.branches||{}).filter(b=>b.canvas).map(b=>b.canvas.checkActivate(branchKey))
    )(state)

    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branches cleanup
    return ({branchHandlers,checkActivate})
}
