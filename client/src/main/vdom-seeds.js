
// functional

import {chain,lensProp,branchProp,branchesActiveProp,parentNodesProp,singleParentNode,ctxToProp,calcPos,elementPos} from "../main/util"
import {rootCtx,ctxToPath} from "../main/vdom-util"

export default function VDomSeeds(log){
    const seed = ctx => parentNode => {
        const rCtx = rootCtx(ctx)
        const fromKey = rCtx.branchKey + ":" + ctxToPath(ctx)
        const branchKey = ctx.value[1]
        return parentNodesProp(branchKey).compose(lensProp(fromKey)).set(parentNode)
    }

    const root = ctx => rootNativeElement => {
        const branchKey = ctx.value[1]
        return branchProp(branchKey).modify(branch=>({...branch,rootNativeElement}))
    }

    const setRootBox = (branchKey,rootBox,style) => {
        const bProp = branchProp(branchKey)
        const styleProp = ctxToProp({key:"style",parent:{key:"at",parent:{branchKey}}},null)
        return chain([bProp.modify(branch=>({...branch,rootBox})), styleProp.set(style)])
    }

    const checkSetRootBox = branchKey => state => {
        const bProp = branchProp(branchKey)
        const branch = bProp.of(state)
        const containerElement = singleParentNode(branchKey)
        const contentElement = branch && branch.rootNativeElement
        const wasBox = branch && branch.rootBox
        if(!containerElement || !contentElement)
            return wasBox ? setRootBox(branchKey,null,{display:"none"})(state) : state
        const contentPos = elementPos(contentElement)
        const containerPos = elementPos(containerElement)
        const targetPos = containerPos // todo f(containerPos,contentPos)
        const d = {
            pos:  calcPos(dir=>(targetPos.pos[dir] -contentPos.pos[dir] )|0),
            size: calcPos(dir=>(targetPos.size[dir]-contentPos.size[dir])|0)
        }
        if(!(d.pos.x||d.pos.y||d.size.x||d.size.y)) return state
        const box = {
            pos:  calcPos(dir => (wasBox ? wasBox.pos[dir] : 0) + d.pos[dir] ),
            size: calcPos(dir => (wasBox ? wasBox.size[dir]: 0) + d.size[dir])
        }
        return setRootBox(branchKey,box,{
            border: "1px solid blue",
            display: "block",
            position: "absolute", //"fixed",
            left: box.pos.x+"px",
            top: box.pos.y+"px",
            width: box.size.x+"px",
            height: box.size.y+"px"
        })(state)
    }

    const checkActivate = state => {
        const [skip,...active] = branchesActiveProp.of(state) || []
        return chain(active.map(checkSetRootBox))(state)
    }

    const ref = ({seed,root})
    const transforms = ({ref})
    return ({transforms,checkActivate})
}

