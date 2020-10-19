
/**
 * useScroll React custom hook
 * Usage:
 *    const { scrollX, scrollY, scrollDirection } = useScroll();
 */

import { useState, useEffect } from '../main/react-prod.js';

export function useScroll() {
  const [lastScrollTop, setLastScrollTop] = useState(0);
  const [bodyOffset, setBodyOffset] = useState(
    document.body.getBoundingClientRect()
  );
  const [scrollY, setScrollY] = useState(bodyOffset.top);
  const [scrollX, setScrollX] = useState(bodyOffset.left);
  const [scrollDirection, setScrollDirection] = useState();

  const listener = e => {
    setBodyOffset(document.body.getBoundingClientRect());
    setScrollY(-bodyOffset.top);
    setScrollX(bodyOffset.left);
    setScrollDirection(lastScrollTop > -bodyOffset.top ? "down" : "up");
    setLastScrollTop(-bodyOffset.top);
  };

  useEffect(() => {
    window.addEventListener("scroll", listener);
    return () => {
      window.removeEventListener("scroll", listener);
    };
  });

  return {
    scrollY,
    scrollX,
    scrollDirection
  };
}

/*

    const body = document.body
    const scrollClass = "scroll-body"
    const hideOnScroll = "hide-on-scroll"
    const minScroll = 5
    let lastScroll = 0
    let startBack = 0
    let goDown = false

    window.addEventListener("scroll", () => {
      const topElements = document.querySelectorAll(".top-row")
      const theDoc = document.getElementById("theDoc")

      const currentScroll = window.pageYOffset
      if (currentScroll == 0) {
        body.classList.remove(scrollClass)
        theDoc.style.marginTop = '0'
        startBack = 0
        goDown = false
        return
      }

      var tth = 0
      topElements.forEach(el => tth += el.offsetHeight)
      if (currentScroll > lastScroll + minScroll) {
        goDown = false
      } else if (!goDown && currentScroll < lastScroll - minScroll) {
        startBack = lastScroll - tth
        if (startBack < 0) startBack = 0
        goDown = true
      }

      //if (!goUp && !goDown) return

      var spaceUsed = 0
      var fixedTop = 0
      var floatingPos = startBack - currentScroll
      if (floatingPos > 0) floatingPos = 0
      topElements.forEach(el => {
        const isFixed = !el.classList.contains(hideOnScroll)
        const theTop = isFixed ? (fixedTop < floatingPos? floatingPos: fixedTop) : floatingPos
        el.style.top = theTop + 'px'
        floatingPos += el.offsetHeight
        if (isFixed) fixedTop += el.offsetHeight
        if (theTop + el.offsetHeight > spaceUsed) spaceUsed = theTop + el.offsetHeight
      })
      if (spaceUsed<0) spaceUsed = 0

      body.classList.add(scrollClass)
      theDoc.style.marginTop = tth + 'px'
      const ths = document.querySelectorAll("th")
      var trs={}
      ths.forEach(th => {
        const tr = th.parentElement
        if (tr.rowIndex == 0)
           th.style.top = spaceUsed + 'px'
        else { // multiply headers support
           var heightAbove = trs[tr]
           if (heightAbove == null) {
              const tabHead = tr.parentElement
              heightAbove = 0
              for (var i=0; i<tr.rowIndex;i++) heightAbove += tabHead.children[i].offsetHeight
              trs[tr] = heightAbove
           }
           th.style.top = (heightAbove + spaceUsed) + 'px'
        }
      })
      lastScroll = currentScroll
    });
*/

const useHideOnScroll = ((rElement) => {

})()


const eventManager = (()=>{
	let w
    const checkForEvent = (event) => typeof event === "function"
    const getHostNoFailure = (location) => {
        try{
             location.host()
             return true
        } catch (e) {
            return false
        }
    }


	const getParentW = (w) => {
		let _w = w
		while(_w && _w.parent != _w && getHostNoFailure(_w.parent.location)) {
            console.log(_w.parent.location);
            _w = _w.parent
        }
		return _w
	}
	const create = el=>(type,params) => {
		const doc =  (el.ownerDocument || el.documentElement.ownerDocument)
		w = getParentW(doc.defaultView)
		switch(type){
			case "keydown": if(checkForEvent(w.KeyboardEvent)) return (new w.KeyboardEvent(type,params)); break				
			case "click": if(checkForEvent(w.MouseEvent)) return (new w.MouseEvent(type,params)); break
			case "mousedown": if(checkForEvent(w.MouseEvent)) return (new w.MouseEvent(type,params)); break			
		}						
		const _params = params && (params.code =="vk" && type =="keydown")?{...params,detail:params}:params //ie11 hack
		return (new w.CustomEvent(type,_params))
	}	
	const sendToWindow = (event)=> w.dispatchEvent(event)
	return {create,sendToWindow}
})()

const textSelectionMonitor = event =>{	
    const getSelection = w => w.document.getSelection()
    const elem = event.target
    const w = elem && elem.ownerDocument && elem.ownerDocument.defaultView		        
    const selection = getSelection(w)
    return selection.toString().length>0 && (!selection.anchorNode || elem.hasChildNodes(selection.anchorNode))	
}
const branches = (()=>{
    let main =""
    const store = (o)=>{
        if(!o.length) return
        main = o.split(/[,;]/)[0]
    }
    const get = () => main
    const isSibling = (o) => {
        if(!o) return false
        if(main.length==0) return false
        return main != o.branchKey
    }
    return {store, get, isSibling}
})()
const checkActivateCalls=(()=>{
	const callbacks=[]
	const add = (c) => callbacks.push(c)
	const remove = (c) => {
		const index = callbacks.indexOf(c)
		if(index>=0) callbacks.splice(index,1)
	}
	const check = (modify) => callbacks.forEach(c=>c(modify))	
	return {add,remove,check}
})()
const resizeListener = (() =>{
    const delay = 500
    const callbacks = []
    let wait
    let init
    const onResize = (e) =>{
        const w= e.target.ownerDocument? e.target.ownerDocument.defaultView: e.target
        if(wait) wait = w.clearTimeout(wait)
        wait = w.setTimeout(()=>{
            callbacks.forEach(c=>c())
            wait = null
        },delay)	
    }
    const reg = (o,el)=>{
        if(!init){
            const w = el.ownerDocument.defaultView
            w.addEventListener("resize",onResize)
            init = true
        }
        callbacks.push(o)
        const unreg=()=>{
            const index = callbacks.indexOf(o)
            if(index>=0) callbacks.splice(index,1)
            return null	
        }
        return {unreg}
    }    
    return {reg}
})()

const receivers = (()=>{
    let _rcv = {}
    const add = (o)=> _rcv = {..._rcv, ...o}              
    const get = () => _rcv
    return {add, get}
})()
const globalRegistry = (()=>{
    let _registry = {}
    const add = (o) => _registry = {..._registry, ...o}
    const get = () => _registry
    return {add, get}
})()
export {eventManager,checkActivateCalls, resizeListener, textSelectionMonitor,branches, receivers, globalRegistry}