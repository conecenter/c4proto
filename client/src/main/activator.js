
// functional root

export default function activate(requestFrame,checkActivate){
    const toListener = reduce => ev => {
        state = reduce(ev)(state)
    }
    let state = {toListener}
    ////
    const checkActivateAll = toListener(ev=>state=>{
        requestFrame(checkActivateAll)
        return checkActivate(state)
    })
    requestFrame(checkActivateAll)
}

//let frames = 0
//setInterval(()=>{ console.log(frames); frames=0 },1000)
//frames++
