
import {spreadAll} from "../main/util"

export function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export function ctxToPath(ctx){
    return !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
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

export const chain = functions => arg => functions.reduce((res,f)=>f(res), arg)

const oneKey = (k,by) => st => {
    const was = st && st[k]
    const will = by(was)
    return was === will ? st : will ? {...(st||{}), [k]: will} :
        spreadAll(...Object.keys(st).filter(ck=>ck!==k).map(ck=>({[ck]:st[ck]})))
}
export const someKeys = bys => chain(Object.keys(bys).map(k=>oneKey(k,bys[k])))
const allKeys = by => state => state ? chain(Object.keys(state).map(k=>oneKey(k,by)))(state) : state
export const dictKeys = f => ({
    one: (k,by) => someKeys(f(oneKey(k,by))),
    all: by => someKeys(f(allKeys(by)))
})

export const branchByKey = dictKeys(f=>({branchByKey:f}))
