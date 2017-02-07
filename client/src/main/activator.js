
export default function activate(requestFrame,checkActivateList){
    //let frames = 0
    //setInterval(()=>{ console.log(frames); frames=0 },1000)
    function checkActivateAll(){
        requestFrame(checkActivateAll)
        checkActivateList.forEach(f=>f())
        //frames++
    }
    requestFrame(checkActivateAll)
}