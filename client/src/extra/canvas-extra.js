
export function OverlayCanvasSetup(canvas){
    function processFrame(frame, prev){
        const {color,viewPos,zoom} = frame
        const same = canvas.compareFrames(frame, prev)
        const mainContext = canvas.composeFrameStart(frame, prev, "preparingCtx",
            same(p=>p.color) &&
            same(p=>p.dragItemPosDiff) &&
            same(p=>p.animState)
        )
        if(!mainContext) return;
        if(!same(p=>p.color)&&frame.dragItemPosDiff<1)
            mainContext.canvas.style.cursor = color ? "pointer" : ""
        const mainContextName = "overlayCtx"
        const overColor = color
        const dragEnded=frame.dragEnded
        const commands = canvas.fromServer().commands||[]
        const mainScale = canvas.zoomToScale(zoom)
        const mainTranslate = canvas.calcPos(dir=>-viewPos[dir])
        canvas.setupContext({ dragEnded,mainContextName, mainContext, overColor, mainTranslate, mainScale, commands }).draw()
    }
    function setupContext(utx){ return {
        over(evColor,commands){
            if(utx.dragEnded) return;
            if(utx.overColor === evColor)
                utx.run(commands)
        }
    }}
    return {setupContext,processFrame}
}

export function ScrollViewPositionCanvasSetup(canvas){
    function handleWheel(ev){
        const mainNode=canvas.fromServer().parentNode
        var parentNode=mainNode.parentNode
        while(parentNode!=document){
            if(mainNode.getBoundingClientRect().height>parentNode.getBoundingClientRect().height){
                parentNode.scrollTop=(parentNode.scrollTop+ev.deltaY*6)
            }
            parentNode=parentNode.parentNode
        }
    }
    function processFrame(frame,prev){
        if(!prev) canvas.visibleElement().addEventListener("wheel",handleWheel)
    }
    function setupFrame(){
        const {viewExternalSize,viewExternalPos,scrollPos,parentPos} = canvas.viewPositions(false)
        const viewPos = canvas.calcPos(dir=>Math.max(0, scrollPos.pos[dir] - parentPos.pos[dir])|0)
        return {viewExternalSize,viewExternalPos,viewPos}
    }
    return {setupFrame,processFrame}
}

export function BoundTextCanvasSetup(canvas){
    function setupContext(utx){
        const ellipsisChar="...";
        function contextMeasureWidth(ctx,text){
            const metrics=ctx.measureText(text);
            return metrics.width;
        };
        function modLast(ctx, minX, maxX, dataAcc){
            var lastLine=dataAcc[dataAcc.length-1].line;
            var lastLineWidth=contextMeasureWidth(ctx,lastLine);
            if(lastLineWidth>(maxX-minX))
                do{
                    lastLine=lastLine.substring(0,lastLine.length-1);
                }while(lastLine.length>0&&contextMeasureWidth(ctx,lastLine+ellipsisChar)>(maxX-minX));
            else return;
            dataAcc[dataAcc.length-1].line=lastLine + ellipsisChar;
        };
        function splitBy(text){
            const arr=text.split(" ");
            arr.forEach(function(e1,i){
                const arr2=e1.split("/");
                if(arr2.length>0) arr.splice(i,1);
                arr2.forEach(function(e2,j){
                    arr.splice(i+j,0,(j!=arr2.length-1?e2+"/":e2));
                });
            });
            return arr;
        };
        function fillWrapText(context, command, minX, minY, maxX, maxY, centerOnHeight, noWrap, lineHeight) {
            const text=command[0];
            const x=command[1];
            const y=command[2];
            const dataAcc=[];
            const words = splitBy(text);
            var line = '';
            var offsetY = 0;
            if(noWrap) line=text;
            else{
                for(let n = 0; n < words.length; n++) {
                  const testLine = line + words[n] + (words[n].charAt(words[n].length-1)!='/'?' ':"");
                  const testWidth = contextMeasureWidth(context,testLine);
                  if (testWidth > (maxX - minX) && n > 0 && offsetY+2*lineHeight<(maxY - minY)) {
                    dataAcc.push({line,x,y:y+offsetY});
                    line = words[n] + (words[n].charAt(words[n].length-1)!='/'?' ':"");
                    offsetY += lineHeight;
                  }
                  else {
                    line = testLine;
                    if(n==0&&testWidth>(maxX - minX))
                        break;
                  }
                }
            }

            dataAcc.push({line,x,y:y+offsetY});

            if(centerOnHeight){
                const lastY=dataAcc[dataAcc.length-1].y;
                const yAdjust=(lastY)/2;
                dataAcc.forEach((e)=>{e.y=e.y-yAdjust;});
            }
            modLast(context,minX,maxX,dataAcc);
            dataAcc.forEach((e)=>{
                context.fillText(e.line, e.x, e.y);
            });

        };
        function run(commands, minX, minY, maxX, maxY, lineHeight, centerOnHeight, noWrap){
            for(let i=0; commands[i]; i+=2){
             const cmd = commands[i+1]
             const ctx = cmd in utx ? utx : utx.ctx
             if(cmd==="fillText")
                fillWrapText(ctx, commands[i], minX, minY, maxX, maxY, centerOnHeight, noWrap, lineHeight);
             else
                ctx[cmd].apply(ctx, commands[i])
            }
        };
        return {
            boundText(name, minX, minY, maxX, maxY, lineHeight, centerOnHeight, noWrap, commands){
                if(name !== utx.mainContextName) return;
                utx.ctx = utx.mainContext;
                utx.ctx.save();
                run(commands, minX, minY, maxX, maxY, lineHeight, centerOnHeight, noWrap);
                utx.ctx.restore();
            },
        }
    }
    return {setupContext}
}

export function DragAndDropCanvasSetup(canvas){
    let dragOverEl=undefined;
    let evColorList={};
    let dragGrabbed=undefined;
    let dragItemPosDiff=0;
    let mouseDownHandler=false;
    let grabHoldCounter=null;
    let dragStarted=false;
    let dragEnded=false;

    function resetDelay(d){
        clearInterval(grabHoldCounter);
        grabHoldCounter=null;
    };
    function resetDragObject(){
        if(dragGrabbed){
            dragGrabbed.x=dragGrabbed.prev.x;
            dragGrabbed.y=dragGrabbed.prev.y;
        }
    };
    function delayed(by,p){
        let msCount=0;
        grabHoldCounter=setInterval(()=>{
                msCount+=1;
                if(msCount>by) {
                    p();
                    resetDelay();
                }},50);
    }
    function extraDragIsActive(){
        return dragGrabbed;
    };
    function checkColorInList(evColor){
        if(!evColorList[evColor]) evColorList[evColor]={x:0,y:0,prev:{x:0,y:0}};
    };
    function handleKeyUp(ev){
        switch(ev.keyCode){
            case 27:
                if(dragStarted){
                    const mousePos=canvas.getMousePos();
                    reportToServer(mousePos,"","dragEndColor");
                }
                break;
            default:break;
        }

    };
    function handleMouseUp(){
        resetDelay();
        if(dragGrabbed&&dragStarted){
            const mousePos=canvas.getMousePos();
            const mousePosDiff = {x:mousePos.x-dragGrabbed.savedInitPos.x,y:mousePos.y-dragGrabbed.savedInitPos.y};
            const from = canvas.setupFrame();
            reportToServer(mousePosDiff,from.color,"dragEndColor");
        }
        resetDragObject();
        if(dragStarted)
            dragEnded=true;
        dragStarted=false;
	    dragGrabbed=undefined;
	    //dragOverEl=undefined;
	    dragItemPosDiff=0;
    };
    function handleMouseDown(ev){
        if(dragOverEl){
            const mousePos=canvas.getMousePos();
            const sDragOverEl=dragOverEl;
            delayed(1,()=>{
                if(!sDragOverEl) return;
                dragGrabbed=sDragOverEl;
                dragGrabbed.initPos=mousePos;
            });
        }
        ev.preventDefault();
    };
    function drag(dragEvent){
        if(dragGrabbed&&!dragEvent.isLast){
            const mousePos=dragEvent.mousePos;
            if(dragGrabbed.initPos){
                const color = Object.keys(evColorList).find((k)=>{return evColorList[k]==dragGrabbed});
                //const color=canvas.getImageData(mousePos);
                if(!color) return;
                //mousePos.prev.x=dragGrabbed.initPos.x;
                //mousePos.prev.y=dragGrabbed.initPos.y;
                dragGrabbed.savedInitPos=dragGrabbed.initPos;
                //dragGrabbed.initPos=null;
                dragStarted=true;
                const mousePosDiff={x:0,y:0};
                reportToServer(mousePosDiff,color,"dragStartColor");
            }
            moveEl(mousePos);
        }
        if(dragEvent.isLast){
            handleMouseUp();
        }
    };
    function reportToServer(mousePosDiff,color,eventType){
        //const prPos = canvas.relPos(canvas.fromServer().parentNode(), mousePosDiff); // has zk id
        const {zoom,viewPos} = canvas.setupFrame()
        const mainScale = canvas.zoomToScale(zoom)
        //const rPos = canvas.calcPos(dir=>(prPos[dir]+viewPos[dir])/mainScale)
        const rPos = canvas.calcPos(dir=>mousePosDiff[dir]/mainScale)
        canvas.sendToServer({
            "X-r-canvas-color":(color?color:""),
            "X-r-canvas-mapX": rPos.x+"",
            "X-r-canvas-mapY": rPos.y+"",
            "X-r-canvas-eventType": eventType
        });
        console.log(eventType,(color?color:""),rPos);
        if(!color) return false;
        return true;
    };
    function moveEl(mousePos){
        if(!dragGrabbed) return;
        if(dragGrabbed.initPos){
            dragGrabbed.x-=dragGrabbed.initPos.x-mousePos.x;
            dragGrabbed.y-=dragGrabbed.initPos.y-mousePos.y;
            dragGrabbed.initPos=null;
        }
        else{
            dragGrabbed.x-=mousePos.prev.x-mousePos.x;
            dragGrabbed.y-=mousePos.prev.y-mousePos.y;
        }

        dragItemPosDiff+=1;
    };
    function processFrame(frame,prev){
        const same = canvas.compareFrames(frame,prev);

        if(!mouseDownHandler&&Object.keys(evColorList).length){
            window.addEventListener("keyup",handleKeyUp);
            canvas.visibleElement().addEventListener("mousedown",handleMouseDown);
            mouseDownHandler=true;
        }
	    if(!same(p=>p.dragGrabbed)){
	        if(!frame.dragOverEl){
	            canvas.visibleElement().style.cursor='';
	        }
	    }
	    if(!same(p=>p.fromServerVersion)) dragEnded=false;
    };
    function drawWithOffset(utx,commands){
        if(!commands) return;
        if(dragGrabbed){
            utx.ctx=utx.mainContext;
            utx.ctx.save();
            utx.ctx.translate(dragGrabbed.x/utx.mainScale,dragGrabbed.y/utx.mainScale);
            utx.run(commands);
            utx.ctx.restore();
        }
        else
            utx.run(commands);
    };
    function setupContext(utx){
        return{
            dragOver(evColor,name,commands){
                checkColorInList(evColor);
                if(utx.mainContextName==="overlayCtx"){
                    if(!utx.overColor) dragOverEl=undefined;
                    if(!dragGrabbed&&utx.overColor===evColor){
                        dragOverEl=evColorList[evColor];
                    }
                    if(dragGrabbed&&(name=="draggingCtx")){
                      drawWithOffset(utx,commands);
                    }
                }
            },
        };
    };
    function setupFrame(){
        return {dragOverEl,dragItemPosDiff,dragGrabbed,dragEnded};
    };
    return {processFrame,setupContext,setupFrame,drag,extraDragIsActive,reportToServer};
}

export function TransitionCanvasSetup(canvas){
    const fadingCtx={};
    const dT=20;
    //const inSteps=20;
    let mainScale=1;
    let prevTime=Date.now();
    let reverse=false;
    let animState=0;
    function checkSavedAnimCtx(i,_x,_y,_dt,t,r){
        if(!fadingCtx[i]){
           fadingCtx[i]={i,scale:r?0:mainScale,step:0,r,dt:_dt,pdt:_dt,t,x:_x,y:_y,pr:r};
           console.log("new",i);
        }
        const cFading=fadingCtx[i];
        if(cFading.pdt!=_dt||cFading.pr!=r) {
            console.log("refresh",i);
            fadingCtx[i]={i,scale:r?0:mainScale,step:0,r,dt:_dt,pdt:_dt,t,x:_x,y:_y,pr:r};
        }
        const {scale,step,prevScale,x,y,dt} = fadingCtx[i];
        return {scale,step,x,y,dt};
    };
    function processFrame(frame,prev){

        const same = canvas.compareFrames(frame,prev);


        if(!same(p=>p.zoom)){
            for (var k in fadingCtx){
                if(fadingCtx[k].dt>=1&&fadingCtx[k].scale>0) fadingCtx[k].scale=mainScale;
            }
            animState++;
        }
        if(Date.now()-prevTime>dT){
            for(var key in fadingCtx){
                const c=fadingCtx[key];
                if(c.dt==1) continue;
                //c.prevScale=c.scale;
                const inSteps=c.t/dT;
                const scalePerStep=mainScale/inSteps;
                const scalePerPercent=mainScale/100;
                const stepPerPercent=1/inSteps;
                c.dt=c.dt+stepPerPercent;

                c.scale=c.r?(mainScale*c.dt):(mainScale- mainScale*c.dt);
                //c.step=c.step+1;
                animState++;
                if(!c.r&&c.scale<=0) {
                    console.log("done anim out");
                    c.scale=0;
                    c.dt=1;
                }
                if(c.r&&c.scale>=mainScale){
                    console.log("done anim in");
                    c.scale=mainScale;
                    c.dt=1;
                }
                if(animState == Number.MAX_SAFE_INTEGER) animState=0;
            }
            prevTime=Date.now();
        }
    };
    function setupContext(utx){
        return {
            animOut(i,_x,_y,_dt,t,commands){
               if(utx.mainContextName!=="overlayCtx") return;

               const {scale,step,x,y,dt}=checkSavedAnimCtx(i,_x,_y,_dt,t,false);

               mainScale=utx.mainScale;
               utx.mainContext.save();
               const dScale=(scale - mainScale)/utx.mainScale;
               utx.mainContext.translate(-x*dScale,-y*dScale);
               utx.mainContext.scale(scale/utx.mainScale,scale/utx.mainScale);
               utx.run(commands);
               utx.mainContext.restore();
            },
             animIn(i,_x,_y,_dt,t,commands){
               if(utx.mainContextName!=="overlayCtx") return;
               const {scale,step,x,y,dt}=checkSavedAnimCtx(i,_x,_y,_dt,t,true);

               mainScale=utx.mainScale;
               //console.log(i,_dt);
               utx.mainContext.save();
               const dScale=(scale - mainScale)/utx.mainScale;
               //console.log(utx.mainScale)
               utx.mainContext.translate(-x*(dScale),-y*(dScale));
               utx.mainContext.scale(scale/utx.mainScale,scale/utx.mainScale);
               utx.run(commands);
               utx.mainContext.restore();
            },
        };
    };
    function setupFrame(){
        return {animState};
    };
    return {processFrame,setupContext,setupFrame};
}
