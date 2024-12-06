
export const VDomSender = () => ({
    send: (ctx, options) => ctx.branchContext.enqueue(ctx.identity,options)
})

export const Feedback = () => ({
    send: ({url,options}) => fetch(url,options)
})

export const CanvasManager = (useCanvas) => {
    const Canvas = props => {
        const [parentNode, ref] = useState()
        const style = useCanvas({...props, parentNode})
        return createElement("div",{ style, ref })
    }
    return {components:{Canvas}}
}