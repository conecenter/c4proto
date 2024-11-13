
// @ts-check
import {createElement,useState,useCallback,useEffect,useContext,createContext,useMemo} from "react"

const useStateWrap = (...args) => {
    const [state, setState] = useStateOrig(...args)
    const setStateWrapper = useCallback((...sArgs) => {
        console.trace("setState")
        setState(...sArgs)
    }, [setState])
    return [state, setStateWrapper]
}

export {createElement,useState,useCallback,useEffect,useContext,createContext,useMemo}
