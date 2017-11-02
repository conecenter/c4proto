
export function CanvasUtil(){
    function cached(recalculate){
        const data = {}
        return (...args)=>{
            const k = args.join(":")
            return k in data ? data[k] : (data[k] = recalculate.apply(this,args))
        }
    }
    function temp(recalculate){
        const data = {}
        function get(...args){
            const k = args.join(":")
            const value = k in data ? data[k].value : recalculate.apply(this,args)
            return (data[k] = { lastAccess: Date.now(), value }).value
        }
        function clearOlderThan(time){
            Object.keys(data).forEach(k=>{ if(data[k].lastAccess<time) delete data[k] })
        }
        return {get,clearOlderThan}
    }
    function never(){ throw ["not single"] }
    function setup(traits,joiningRules){
        const single = l => l.length === 1 ? l[0] : never()
        const h = {}
        const dst = {}
        traits(dst).forEach(o=>o && Object.keys(o).forEach(k=>(h[k]||(h[k]=[])).push(o[k])))
        Object.keys(h).forEach(k=>(dst[k] = (joiningRules[k]||single)(h[k])))
        return dst
    }
    return {cached,temp,setup}
}

export function CanvasFactory(util, modList){
    const mods = options => canvas => modList.map(f=>f(options)(canvas)).reduce((p,c)=>p.concat(c), [])
    return options => util.setup(mods(options), {
        drag        : l => frame => l.forEach(s=>s(frame)),
        processFrame: l => (frame,prev) => l.map(s=>s(frame,prev)),
        setupContext: l => opt => util.setup(utx=>l.map(s=>s(utx)).concat(opt), {}),
        setupFrame  : l => () => util.setup(frame=>l.map(s=>s()), {})
    })
}

export function ExchangeCanvasSetup(canvas,scrollNode,rootElement,createElement,activeElement){
    function onZoom(){} //todo to close popup?
    function appendChild(element){
        rootElement().appendChild(element)
        element.style.position = "absolute"
        element.style.zIndex = "554"
		element.style.outline = "none"
		if(element.tagName == "CANVAS"){
			element.tabIndex = "1"
			element.onclick = ()=>{
				const aElement = activeElement?activeElement():null;
				if(aElement&&element!=aElement) aElement.blur&&aElement.blur();
			}			
		}
    }

    return {onZoom,scrollNode,createElement,appendChild}
}
/*
function ElementSystem(){
    function createElement(tagName){ return document.createElement(tagName) }
    function appendChild(element){ document.body.appendChild(element) }
    return {createElement,appendChild}
}
*/
export function ResizeCanvasSystem(util,createElement){
    const fontMeter = util.cached(()=>createElement('div'))
    return ({fontMeter})
}

//state.changedSizes && index >= parseInt(state.changedSizes.sent["X-r-index"]) ? {...state, changedSizes: null} : state
export function ResizeCanvasSetup(canvas,system,getComputedStyle){
    function woPx(value){ return value.substring(0,value.length-2) }
    function processFrame(frame,prev){
        const div = canvas.parentNode()
        const canvasWidth = parseInt(woPx(getComputedStyle(div).width))
        if(!canvasWidth) return;
        const fontMeter = system.fontMeter()
        if(!fontMeter.parentElement){
            fontMeter.style.cssText = "height:1em;padding:0px;margin:0px"
            canvas.appendChild(fontMeter)
        }
        const canvasFontSize = parseInt(woPx(getComputedStyle(fontMeter).height))
        const sizes = canvasFontSize+","+canvasWidth
        if(canvas.fromServer().value !== sizes)
            canvas.fromServer().onChange({ target: { value: sizes } })
    }
    return ({processFrame})
}

export function BaseCanvasSetup(log, util, canvas, system){
    let lastFrame
    let currentState = {}
    let fromServerVersion = 0
    function parentNode(){
        const res = Object.values(currentState.parentNodes||{}).filter(v=>v)
        return res.length === 1 ? res[0] : null
    }

    function sendToServer(req,evColor){ return currentState.sendToServer({ headers: req },evColor)}

    function fromServer(){ return currentState.parsed }
    function checkActivate(state){
        if(currentState.parsed !== state.parsed) updateFromServerVersion()
        currentState = state

        if(!canvas.scrollNode()) return state
        //const canvasElement = canvas.visibleElement()
        const parentElement = canvas.parentNode()
        if(!parentElement){
            //if(canvasElement.parentNode) canvasElement.parentNode.removeChild(canvasElement)
            return state
        }
        const newFrame = canvas.setupFrame()
        // in setupFrame we gather data from dom and place it to imm frame
        // in processFrame we use gathered data to update dom
        // diff is moved out of setupFrame, to allow merging data from different modules?
        canvas.processFrame(newFrame, lastFrame)
        lastFrame = newFrame
        //console.log("canvas-gen-time",Date.now()-startTime)
        return state
    }
    function remove() {
        const canvasElement = canvas.visibleElement()
        if(canvasElement.parentNode) canvasElement.parentNode.removeChild(canvasElement)
    }

    ////
    function setupFrame(){
        return {startTime: Date.now(), fromServerVersion}
    }
    function processFrame(frame, prev){
        const canvasElement = canvas.visibleElement()
        if(!canvasElement.parentNode) canvas.appendChild(canvasElement)
        const same = canvas.compareFrames(frame,prev)
        const samePos = comparePos(same)
        if(!samePos(p=>p.viewExternalPos)){
            canvasElement.style.left = frame.viewExternalPos.x+"px"
            canvasElement.style.top = frame.viewExternalPos.y+"px"
        }
    }
    function viewPositions(infinite){
        const parentPos = canvas.elementPos(canvas.parentNode())
        const scrollPos = canvas.elementPos(canvas.scrollNode())
        const vExternalPos = canvas.calcPos(dir=>Math.max(parentPos.pos[dir],scrollPos.pos[dir])|0)
        const canvasElement = canvas.visibleElement()
        const canvasPos = canvas.elementPos(canvasElement)
        const x = (vExternalPos.x + (parseInt(canvasElement.style.left)||0) - canvasPos.pos.x)|0
        const y = (vExternalPos.y + (parseInt(canvasElement.style.top)||0)  - canvasPos.pos.y)|0
        const viewExternalPos = {x,y}
        const parentPosEnd = { x: parentPos.end.x|0, y: infinite ? Infinity : parentPos.end.y|0 }
        const vExternalEnd = canvas.calcPos(dir=>Math.min(parentPosEnd[dir],scrollPos.end[dir])|0)
        const viewExternalSize = canvas.calcPos(dir=>Math.max(0, vExternalEnd[dir] - vExternalPos[dir])|0)
        return {viewExternalSize,viewExternalPos,scrollPos,parentPos}
    }

    const composingElement = util.cached(name => createCanvas());
    function visibleElement(){ return canvas.composingElement("preparingCtx") }
    ////
    function calcPos(calc){ return { x:calc("x"), y:calc("y") } }
    function elementPos(element){
        const p = element.getBoundingClientRect()
        return {pos:{x:p.left,y:p.top}, size:{x:p.width,y:p.height}, end:{x:p.right,y:p.bottom}}
    }
    function createCanvas(){ return canvas.createElement('canvas') }
    function fixCanvasSize(canvasElement,size){
        canvasElement.width = size.x
        canvasElement.height = size.y
    }
    function createCanvasWithSize(sz){
        const canvasElement = createCanvas()
        fixCanvasSize(canvasElement,sz)
        return canvasElement
    }
    function getContext(canvasElement){ return canvasElement.getContext("2d") }
    function cleanContext(ctx){
        ctx.clearRect(0,0,ctx.canvas.width,ctx.canvas.height)
    }
    ////
    function setupContext(utx){ return {
        set(k,v){ utx.ctx[k] = v },
        definePath(name,commands){ utx[name] = () => utx.run(commands) },
        setMainContext(commands){ utx.ctx = utx.mainContext },
        inContext(name,commands){
            if(name !== utx.mainContextName) return;
            utx.ctx = utx.mainContext
            utx.ctx.save()
            utx.run(commands)
            utx.ctx.restore()
        },
        run(commands){
            for(let i=0; commands[i]; i+=2){
                const cmd = commands[i+1]
                const ctx = cmd in utx ? utx : utx.ctx
                ctx[cmd].apply(ctx, commands[i])
            }
        },
        draw(){
            const {mainScale,mainContext,mainTranslate,commands} = utx
            mainContext.save()
            mainContext.setTransform(mainScale, 0, 0, mainScale, mainTranslate.x, mainTranslate.y) // translate, scale
            utx.run(commands)
            mainContext.restore()
        }
    }}
    ////
    //...commandZoom, maxZoom from server
    function zoomToScale(zoom){
        return Math.exp(((zoom||0)-(fromServer().commandZoom||0))/fromServer().zoomSteps)
    }
    function mapSize(){
        return { x: Math.round(canvas.fromServer().width), y: Math.round(canvas.fromServer().height) }
    }
    function compareFrames(frame,prev){
        return cond => prev && cond(frame)===cond(prev)
    }
    function comparePos(same){
        return toPos => same(p=>toPos(p).x) && same(p=>toPos(p).y)
    }
    const filledElements = util.temp((fromServerVersion,tileZoom,mainContextName,x,y,w,h) => {
        //console.log("filledElements",tileZoom,mainContextName,x,y,w,h)
        const canvasElement = canvas.createCanvasWithSize({x:w,y:h})
        const mainContext = canvas.getContext(canvasElement)
        const commands = canvas.fromServer().commands || []
        const mainScale = canvas.zoomToScale(tileZoom)
        const viewTilePos = {x,y}
        const mainTranslate = canvas.calcPos(dir=>-viewTilePos[dir])
        canvas.setupContext({ mainContextName, mainContext, mainScale, mainTranslate, commands }).draw()
        return canvasElement
    })
    function composeFrameStart(frame,prev,ctxName,sameAdd){//viewPos need integers
        const {startTime,fromServerVersion,viewExternalSize,viewPos,zoom,zoomIsChanging,tileZoom} = frame
        const same = canvas.compareFrames(frame,prev)
        const samePos = comparePos(same)
        if(
            sameAdd && same(p=>p.fromServerVersion) &&
            samePos(p=>p.viewPos) && samePos(p=>p.viewExternalSize) &&
            same(p=>p.zoom) && same(p=>p.tileZoom)
        ) return;

        const canvasElement = canvas.composingElement(ctxName)
        //if(!samePos(p=>p.viewExternalSize)) log({viewExternalSize,ctxName})
        if(!samePos(p=>p.viewExternalSize)) fixCanvasSize(canvasElement,viewExternalSize)
        if(!viewExternalSize.x || !viewExternalSize.y) return;
        const ctx = canvas.getContext(canvasElement)
        canvas.cleanContext(ctx)
        //
        const tileScale = canvas.zoomToScale(tileZoom)
        const screenScale = canvas.zoomToScale(zoom)
        canvas.forTiles(frame, screenScale, tileScale, (tilePos,tileSize)=>{
            const srcElement = filledElements.get(fromServerVersion,tileZoom,ctxName,tilePos.x,tilePos.y,tileSize.x,tileSize.y)
            const dstPos = canvas.calcPos(dir=>tilePos[dir]/tileScale*screenScale-viewPos[dir])
            const dstSize = canvas.calcPos(dir=>tileSize[dir]/tileScale*screenScale)
            ctx.drawImage(srcElement,
                0, 0, tileSize.x, tileSize.y,
                dstPos.x, dstPos.y, dstSize.x, dstSize.y
            )
        })
        filledElements.clearOlderThan(startTime-2000)
        return ctx
    }
    function updateFromServerVersion(){ fromServerVersion++ }
    return {
        setupContext,cleanContext,getContext,calcPos,fromServer,
        composingElement,visibleElement,mapSize,createCanvasWithSize,
        setupFrame,processFrame,viewPositions,composeFrameStart,
        checkActivate, remove,
        zoomToScale, compareFrames, elementPos, updateFromServerVersion,
        parentNode, sendToServer
    }
}

export function ComplexFillCanvasSetup(util, canvas){
    const image = util.cached(url=>{
        const image = canvas.createElement("img")
        image.onload = () => canvas.updateFromServerVersion()
        image.src = url
        return image
    })
    const bgCanvas = util.cached((x,y)=>canvas.createCanvasWithSize({x,y}))
    function setupContext(utx){ return {
        definePattern(name,w,h,commands){
            if("preparingCtx" !== utx.mainContextName) return;
            utx.ctx = canvas.getContext(bgCanvas(w,h))
            canvas.cleanContext(utx.ctx)
            utx.run(commands)
            const pattern = utx.mainContext.createPattern(utx.ctx.canvas,"repeat")
            utx[name] = () => {
                if(utx.ctx === utx.mainContext) utx.ctx.fillStyle = pattern
                else throw 'bad ctx'
            }
        },
        linearGradient(x0, y0, x1, y1, stops){
            const grd = utx.ctx.createLinearGradient(x0, y0, x1, y1)
            stops.forEach(s=>grd.addColorStop(s[0], s[1]))
            utx.ctx.fillStyle = grd
        },
        image(name,url,x,y,w,h){
            if(name === utx.mainContextName){
                utx.mainContext.drawImage(image(url),x,y,w,h)
            }
        }
    }}
    return ({setupContext})
}

export function SingleTileCanvasSetup(canvas){
    function forTiles(frame, screenScale, tileScale, compose){
        const mapSize = canvas.mapSize()
        compose({x:0,y:0},canvas.calcPos(dir=>(mapSize[dir]*tileScale)|0))
    }
    return ({forTiles})
}

export function TiledCanvasSetup(canvas){
    function forTiles(frame, screenScale, tileScale, compose){
        const {viewPos,viewExternalSize} = frame
        const tileSize = { x: 2000, y: 1400 } // need to be < 2048 side and < 3M all for some devices
        const viewTilePos = canvas.calcPos(dir=> (viewPos[dir]/screenScale*tileScale)|0)
        const viewTileSize = canvas.calcPos(dir=> viewExternalSize[dir]/screenScale*tileScale)
        const viewEnd = canvas.calcPos(dir=> viewTilePos[dir] + viewTileSize[dir])
        const firstTilePos =
            canvas.calcPos(dir=> viewTilePos[dir] - viewTilePos[dir] % tileSize[dir])
        for(let x = firstTilePos.x; x < viewEnd.x; x+=tileSize.x)
            for(let y = firstTilePos.y; y < viewEnd.y; y+=tileSize.y)
                compose({x,y},tileSize)
    }
    return ({forTiles})
}

export function MouseCanvasSystem(util,addEventListener){
    let currentDrag = noDrag
    let mousePos = { x:0, y:0, t:0 }
    const needMouseWindowListeners = util.cached(()=>{
        addEventListener("mousemove",ev=>currentDrag(ev,false),false)
        addEventListener("mouseup",ev=>currentDrag(ev,true),false) // capture (true) causes popup close to be later
        return true
    })
    function getMousePos(){ return mousePos }
    function regMousePos(ev, prev){
        return mousePos = { prev, x:ev.clientX, y:ev.clientY, t:Date.now() }
    }
    function noDrag(ev,isLast){
        regMousePos(ev, null)
    }
    function setCurrentDrag(f){ currentDrag = f || noDrag }

    return {getMousePos,regMousePos,setCurrentDrag,needMouseWindowListeners}
}

export function MouseCanvasSetup(canvas, system){
    function getMousePos(){ return system.getMousePos() }
    function handleMouseDown(ev){
        system.regMousePos(ev, null)
        system.setCurrentDrag((ev,isLast) => {
            const mousePos = system.regMousePos(ev, canvas.getMousePos())
            canvas.drag({isLast,mousePos})
            //console.log("drag",isLast)
            if(isLast) system.setCurrentDrag(null)
        })
        ev.preventDefault()
    }
    function processFrame(frame, prev){
        system.needMouseWindowListeners()
        if(!prev) canvas.visibleElement().addEventListener("mousedown", handleMouseDown)//ie selectstart?
    }
    function dMousePos(p1,p0){ return { x: p1.x-p0.x, y: p1.y-p0.y, t: p1.t-p0.t } }
    function findMousePos(pLast, cond){
        for(let p=pLast.prev;p;p=p.prev) if(cond(p)) return p
    }
    function relPos(el, pos){
        const rect = canvas.elementPos(el)
        return canvas.calcPos(dir=>pos[dir] - rect.pos[dir])
    }
    return {processFrame,dMousePos,findMousePos,getMousePos,relPos}
}

export function NoOverlayCanvasSetup(canvas){
    function processFrame(frame, prev){
        canvas.composeFrameStart(frame,prev,"preparingCtx",true)
    }
    function setupContext(utx){ return {
        over(evColor,commands){}
    }}
    return {setupContext,processFrame}
}

export function InteractiveCanvasSetup(canvas){
    function getImageData(mousePos){ //private
        const reactiveCanvasElement = canvas.composingElement("reactiveCtx")
        const reactiveContext = canvas.getContext(reactiveCanvasElement)
        const rPos = canvas.relPos(canvas.visibleElement(), mousePos)
        const data = reactiveContext.getImageData(rPos.x-1,rPos.y-1,3,3)
        function getColor(x,y){  //return;
            const p = (x+1)*4 + (y+1)*data.width*4
            return data.data[p+3]===255 ? "rgb("+data.data.slice(p,p+3).join(",")+")" : undefined
        }
        const color = getColor(0,0)
        if(color !== getColor(-1,0) && color !== getColor(1,0)) return;
        if(color !== getColor(0,-1) && color !== getColor(0,1)) return;
        return color
    }
    function drag(dragEvent){
        const mousePos = dragEvent.mousePos
        if(!dragEvent.isLast || canvas.findMousePos(mousePos, p => {
            const d = canvas.dMousePos(mousePos,p)
            //console.log(d)
            return d.t > 200 || d.x*d.x + d.y*d.y > 100
        })) return;
        const color = getImageData(mousePos)
        if(!color) return;
        const rPos = canvas.relPos(canvas.parentNode(), mousePos) // has zk id
        canvas.sendToServer({
            "X-r-canvas-color": color,
            "X-r-canvas-rel-x": rPos.x+"",
            "X-r-canvas-rel-y": rPos.y+"",
            "X-r-action": "clickColor"
        },color)
    }
    function setupFrame(){
        return {color: getImageData(canvas.getMousePos())}
    }
    function processFrame(frame, prev){
        if(frame.zoomIsChanging) return;
        const same = canvas.compareFrames(frame, prev)
        canvas.composeFrameStart(frame,prev,"reactiveCtx",same(p=>p.zoomIsChanging))
    }
    return {setupFrame,processFrame,drag}
}

export function DragViewPositionCanvasSetup(canvas){
    let animation
    function setupFrame(){
        return (animation||animateStableZoom(canvas.fromServer().commandZoom||0,time=>canvas.calcPos(dir=>0)))(Date.now())
    }
    function dragPos(from,mousePos){
        const dPos = canvas.dMousePos(mousePos, mousePos.prev)
        const viewPos = canvas.calcPos(dir=>from.viewPos[dir] - dPos[dir])
        animation = animateStableZoom(from.zoom, time=>viewPos)
    }
    function dropPos(from,mousePos){
        const mousePosR = canvas.findMousePos(mousePos, p => mousePos.t-p.t > 50)
        if(!mousePosR) return;
        const d = canvas.dMousePos(mousePos,mousePosR)
        const k = 300
        animation = animateStableZoom(from.zoom, time => canvas.calcPos(dir => from.viewPos[dir] + d[dir] / d.t * k * (Math.exp((mousePos.t-time)/k)-1)))
    }
    function drag(dragEvent){
        if(canvas.extraDragIsActive && canvas.extraDragIsActive()) return;
        const mousePos = dragEvent.mousePos
        const from = canvas.setupFrame();
        (dragEvent.isLast?dropPos:dragPos)(from, mousePos)
    }
    function handleWheel(ev){
        if(ev.ctrlKey) return;
        const time = Date.now()
        const mousePos = { x:ev.clientX, y:ev.clientY, t:time }
        const mouseRelPos = canvas.relPos(canvas.visibleElement(), mousePos)
        const from = canvas.setupFrame()
        animation = animateChangingZoom(from, - Math.sign(ev.deltaY)/2, mouseRelPos)
        canvas.onZoom()
		ev.preventDefault();
    }
    function limit(from, to){
        return value => Math.max(from, Math.min(value, to)) | 0
    }
    function limitPos(zoom, viewExternalSize, pos){
        const mapSize = canvas.mapSize()
        const screenScale = canvas.zoomToScale(zoom)
        const maxViewPos = canvas.calcPos(dir=> mapSize[dir]*screenScale - viewExternalSize[dir])
        return canvas.calcPos(dir => limit(0,maxViewPos[dir])(pos[dir]) )
    }
    function animateStableZoom(zoom, getPos){//undefined+1 is NaN, NaN!==NaN
        return time => {
            const {viewExternalSize,viewExternalPos} = canvas.viewPositions(true)
            const viewPos = limitPos(zoom, viewExternalSize, getPos(time))
            return {time,viewExternalSize,viewExternalPos,limitedTargetZoom:zoom,zoom,tileZoom:zoom,viewPos}
        }
    }
    function animateChangingZoom(from,d,mouseRelPos){
        const zoomSteps = canvas.fromServer().zoomSteps
        const targetZoom = from.limitedTargetZoom + d * zoomSteps
        const tempTileZoom = from.tileZoom > targetZoom ? targetZoom - zoomSteps : from.tileZoom
        const mapSize = canvas.mapSize()
        const fromScale = canvas.zoomToScale(from.zoom)
        const pointPos = canvas.calcPos(dir => (from.viewPos[dir]+mouseRelPos[dir])/fromScale)
        return time => {
            const {viewExternalSize,viewExternalPos} = canvas.viewPositions(true)
            const animationPeriod = 200
            const passed = time - from.time
            const done = Math.min(passed/animationPeriod, 1)
            const zoomIsChanging = passed < 500
            const minScales = canvas.calcPos(dir=>viewExternalSize[dir]/mapSize[dir])
            const minScale = Math.min(minScales.x,minScales.y)
            const fromServer = canvas.fromServer()
            const limitZoom = limit(Math.min(0,Math.log(minScale) * fromServer.zoomSteps), fromServer.maxZoom||0)
            const zoom = limitZoom(targetZoom*done + from.zoom*(1-done))
            const tileZoom = limitZoom(zoomIsChanging ? tempTileZoom : targetZoom)
            const limitedTargetZoom = limitZoom(targetZoom)
            const scale = canvas.zoomToScale(zoom)
            const viewPos = limitPos(zoom, viewExternalSize, canvas.calcPos(dir => pointPos[dir]*scale - mouseRelPos[dir]))

            //console.log(d,from.limitedTargetZoom,targetZoom,limitedTargetZoom)
            return {time,viewExternalSize,viewExternalPos,limitedTargetZoom,zoom,tileZoom,zoomIsChanging,viewPos}
        }
    }
    function processFrame(frame, prev){
        if(!prev) canvas.visibleElement().addEventListener("wheel", handleWheel)
    }
    return {drag,setupFrame,processFrame}
}










