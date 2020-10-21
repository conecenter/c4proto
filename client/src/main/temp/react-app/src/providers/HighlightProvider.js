import React, { useContext, createContext } from 'react'

const HighlightContext = createContext()

export function useHighlight() { return useContext(HighlightContext) }

export default function HighlightProvider({ children }) {

    function highlightElement(element) {

    }

    const value = {}

    return (
        <HighlightContext.Provider value>
            <style></style>
            {children}
        </HighlightContext.Provider>
    )
}
