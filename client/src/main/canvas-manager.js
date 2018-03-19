export default function CanvasManager(canvasFactory, feedback) {
    const showCanvasData = data =
>
    state =
>
    {
        const parsed = JSON.parse(data)
        const canvas = state.canvas || canvasFactory(parsed.options || {})
        //todo: options are considered only once; do we need to rebuild canvas if they change?
        return ({
            ...state, canvas, parsed,
            remove
    :
        canvas.remove,
            checkActivate
    :
        canvas.checkActivate,
            ackChange
    :
        canvas.ackChange
        })
    }
    const branchHandlers = ({showCanvasData}) // todo branches cleanup
    return ({branchHandlers})
}
