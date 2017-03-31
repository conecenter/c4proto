
export default function CanvasManager(canvasFactory,feedback){
    const showCanvasData = data => state => {
        const canvas = state.canvas || canvasFactory()
        return ({
            ...state, canvas,
            remove: canvas.remove,
            checkActivate: canvas.checkActivate,
            ackChange: canvas.ackChange,
            parsed: JSON.parse(data)
        })
    }
    const branchHandlers = ({showCanvasData}) // todo branches cleanup
    return ({branchHandlers})
}
