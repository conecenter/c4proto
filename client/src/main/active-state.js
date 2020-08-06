
export default function withState(log,handlers){
    let toModify = []
    let state = ({})
    function modify(hint,by){
        toModify = [...toModify,{hint,by}]
        if(toModify.length === 1) try {
            while(toModify.length > 0){
                const nState = toModify[0].by(state)
                if(state!==nState){
                    log({hint,state:nState})
                    state = nState
                }
                toModify = toModify.slice(1)
            }
        } finally {
            if(toModify.length > 0){
                state = ({ ...state, wasModificationError: true })
                toModify = []
            }
        }
    }
    return handlers.map(h => () => h(modify))
}