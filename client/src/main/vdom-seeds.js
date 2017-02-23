
// functional

import {lensProp,branchProp,branchesActiveProp,parentNodesProp,ctxToProp} from "../main/util"
import {rootCtx,ctxToPath} from "../main/vdom-util"

export default function VDomSeeds(log){
    const seed = ctx => parentNode => {
        const rCtx = rootCtx(ctx)
        const fromKey = rCtx.branchKey + ":" + ctxToPath(ctx)
        const branchKey = ctx.value[1]
        return parentNodesProp(branchKey).compose(lensProp(fromKey)).set(parentNode)
    }

    const Follower = (prop) => {


    }

    const checkActivate = state => {
        const active = branchesActiveProp.of(state) || []

        const changes = active.map(branchKey=>state=>{
            if(branchKey===active[0]) ???

            const parentNode = singleParentNode(branchKey)
            if(!parentNode) ???




            return ctxToProp({key:"at",parent:{branchKey}},null).modify(was=>{
                const ref = state.toListener(rootNativeElement => branchProp(branchKey).modify(was=>({...was,rootNativeElement})))
                return ({ref,style})
            })
        })
        return chain(changes)(state)
    }

    const ref = ({seed})
    const transforms = ({ref})
    return ({transforms,checkActivate})
}

/*display:
                         position:
                         top:
                         left:*/
