
export function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

function ctxToPath(ctx){
    return !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
}

export function ctxToBranchPath(ctx){
    const rCtx = rootCtx(ctx)
    return [rCtx, rCtx.branchKey + ":" + ctxToPath(ctx)]
}

export function VDomSender(feedback){ // todo: may be we need a queue to be sure server will receive messages in right order
    const send = (ctx, target) => feedback.send({
        url: "/connection",
        options: {
            headers: {
                ...target.headers,
                "X-r-branch": rootCtx(ctx).branchKey,
                "X-r-vdom-path": ctxToPath(ctx)
            },
            body: target.value
        },
    })
    return ({send})
}

const single = res => fail => res.length === 1 ? res[0] : fail()
const singleParentNode = branch => single(Object.values(branch.parentNodes||{}).filter(v=>v))(()=>null)

export const elementPos = element => {
    const p = element.getBoundingClientRect()
    return {
        pos: {x:p.left,y:p.top},
        size:{x:p.width,y:p.height},
        end:{x:p.right,y:p.bottom}
    }
}
export const calcPos = calc => ({ x:calc("x"), y:calc("y") })

function ctxToArray(ctx,res){
    if(ctx){
        ctxToArray(ctx.parent, res)
        if(ctx.key) res.push(ctx.key)
    }
    return res
}

export function VDomSeeds(log,DiffPrepare){
    const seed = ctx => parentNode => {
        const [rCtx,fromKey] = ctxToBranchPath(ctx)
        const branchKey = ctx.value[1]
        rCtx.modify(branchKey, state=>({
            ...state,
            parentNodes:{
                ...state.parentNodes,
                [fromKey]: parentNode
            }
        }))
    }
    const root = ctx => rootNativeElement => {
        const branchKey = ctx.value[1]
        const rCtx = rootCtx(ctx)
        rCtx.modify(branchKey, state=>({
            ...state,
            checkActivate: checkActivate(ctx),
            rootNativeElement
        }))
    }
    const setRootBox = (rootBox,ctx,style) => state => {
        const path = ctxToArray(ctx,[])
        const rCtx = rootCtx(ctx)
        const diff = DiffPrepare(rCtx.localState)
        diff.jump(path)
        diff.addIfChanged("style", style)
        diff.apply()
        return ({...state,rootBox})
    }

    const checkActivate = ctx => state => {
        if(state.isRootBranch) return state;
        const containerElement = singleParentNode(state)
        const contentElement = state.rootNativeElement
        const wasBox = state.rootBox
        if(!containerElement || !contentElement)
            return wasBox ? setRootBox(null,ctx,{display:"none"})(state) : state
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
            size: calcPos(dir => Math.max(0,(wasBox ? wasBox.size[dir]: 0) + d.size[dir]))
        }
        return setRootBox(box,ctx,{
            border: "1px solid blue",
            display: "block",
            position: "absolute", //"fixed",
            left: box.pos.x+"px",
            top: box.pos.y+"px",
            width: box.size.x+"px",
            height: box.size.y+"px"
        })(state)
    }

    const ref = ({seed,root})
    const transforms = ({ref})
    return ({transforms})
}

export const pairOfInputAttributes = ({value,onChange},headers) => {
    const values = (value+"\n").split("\n").slice(0,2)
    return values.map((value,index)=>({
        key: "input_"+index, value,
        onChange: ev => onChange({target:{
            headers,
            value: values.map((v,i)=>index===i?ev.target.value:values[i]).join("\n")
        }})
    }))
};
