
// functional

import {lensProp,branchProp} from "../main/util"
import {rootCtx,ctxToPath} from "../main/vdom-util"

export default function VDomSeeds(log){
    const parentNodesProp = lensProp("parentNodes")
    const seed = ctx => parentNode => {
        const rCtx = rootCtx(ctx)
        const fromKey = rCtx.branchKey + ":" + ctxToPath(ctx)
        const branchKey = ctx.value[1]
        return branchProp(branchKey).compose(parentNodesProp.compose(lensProp(fromKey))).set(parentNode)
    }
    const ref = ({seed})
    const transforms = ({ref})
    return ({transforms})
}