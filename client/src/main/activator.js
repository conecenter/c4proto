
export default function activate(requestFrame,checkActivateList){
    const modify = transform => { state = transform(state) }
    let state = ({modify})
    function checkActivateAll(){
        requestFrame(checkActivateAll)
        modify(chain(checkActivateList))
    }
    requestFrame(checkActivateAll)
}

//let frames = 0
//setInterval(()=>{ console.log(frames); frames=0 },1000)
//frames++
