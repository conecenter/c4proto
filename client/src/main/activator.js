
// functional root

export default function activate(requestFrame,checkActivateList,chain){
    const toListener = reduce => ev => {
        state = reduce(ev)(state)
    }
    let state = {toListener}
    ////
    const checkActivateAll = toListener(ev=>state=>{
        requestFrame(checkActivateAll)
        return chain(checkActivateList)(state)
    })
    requestFrame(checkActivateAll)
}

//let frames = 0
//setInterval(()=>{ console.log(frames); frames=0 },1000)
//frames++
