

import {createElement,useState,useCallback,useEffect,useContext,createContext,useMemo} from "react"
import {createRoot} from "react-dom/client"

const useStateWrap = <S>(initialState: S | (() => S)) => {
    const [state, setState] = useState(initialState)
    const setStateWrapper: React.Dispatch<React.SetStateAction<S>> = useCallback((...sArgs) => {
        console.trace("setState")
        setState(...sArgs)
    }, [setState])
    return [state, setStateWrapper]
}

export {createRoot,createElement,useState,useCallback,useEffect,useContext,createContext,useMemo}
