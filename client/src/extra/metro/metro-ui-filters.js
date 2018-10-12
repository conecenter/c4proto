"use strict";
import React from 'react'
import {pairOfInputAttributes}  from "../../main/vdom-util"
import Errors from "../../extra/errors"
import {ctxToPath,rootCtx} from "../../main/vdom-util"

export default function MetroUiFilters({log,ui,windowManager,StatefulComponent}){
	const MenuBurger = ui.transforms.tp.MenuBurger
	const $ = React.createElement
	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => callbacks.push(c)
		const remove = (c) => {
			const index = callbacks.indexOf(c)
			if(index>=0) callbacks.splice(index,1)
		}
		const check = () => callbacks.forEach(c=>c())
		return {add,remove,check}
	})();
	const {getComputedStyle} = windowManager
	const filters  = (props) => {
		const keys = props.keys
		const children = keys?props.children.filter(_=>keys.includes(_.key)):props.children
		return $("div",{className:"filters", style:props.style},children)
	}
	
	const calcLines = (fAMap, width) => {
		let t = 0
		let maxWidth = 0
		let lines = []
		fAMap.forEach((e)=>{
			if(t<e.basis) { 				
				t = width - e.basis; 
				t=t>=0?t:0; 
				if(!lines.length) return lines.push({width:e.basis, e:[e]})
				lines.push({width:e.basis, e:[e]})
			}
			else { 
				t-=e.basis;		
				const a = lines[lines.length-1]
				a.e.push(e)
				a.width += e.basis		
			}
		})
		return lines
	}
	const calcMinWidth = (lines) => lines.reduce((a,e)=>{if(a<e.width) return e.width; return a},0)		
	const getMinWidthIndex = (lines) => lines.reduce((a,e,i)=>{if(lines[a].width>e.width) return i; return a},0)
	const enableWhileFit = (fMap,fAMap,lines,finalFWidth) =>{					
		for(let i=0,li=0,le=0;lines.length>0&&i<fMap.length;i++){
			const fa = fMap[i], line = lines[li]
			if(!fa.active && fa!=line.e[le]){
				if(line.width+fa.basis<finalFWidth){fa.active = true;line.width+=fa.basis}							
				else break
			}			
			if(le+1>=line.e.length){ if(li+1<lines.length) {li++;le=0}}
			else le++
		}				
		return fMap
	}
	const optimizeByWidth = (lines) =>{
		if(lines.length>=2){
			let index = getMinWidthIndex(lines)		
			while(index>=1){
				const cur = lines[index-1]
				const ref = lines[1]
				let curE = cur.e[cur.e.length-1]
				while(cur.width>ref.width+curE.basis){
					const a = cur.e.splice(-1,1)[0]
					cur.width -=a.basis 
					if(!a) break
					ref.e.push(a)
					ref.width+=a.basis
					curE = cur.e[cur.e.length-1]
				}
				index--
			}
		}
		return lines		
	}
	const calcMsOpts = (el) => {
		const els = el.querySelectorAll(".msOpt")
		const msOpts = Array.from(els,o=>{
			const rc = o.getBoundingClientRect()
			const width = rc.width
			const cs = getComputedStyle(o)
			const height = rc.height
			return {width:width+parseInt(cs.marginLeft)+parseInt(cs.marginRight),height:height,o}
		})
		const msWidth = msOpts.reduce((a,e)=>(a+e.width),0)
		const fits = (tWidth) => tWidth >= msWidth
		const msHeightF = () => msOpts.reduce((a,e)=>(a<e.height?e.height:a),0)
		return {msWidth,fits,msHeightF}
	}	
	
	class wn extends StatefulComponent {		
		calc(){
			if(!this.el) return
			let bWidth = 0
			if(this.state.shouldBurger){
				const o = this.el.querySelector(".burger")
				bWidth = o?o.getBoundingClientRect().width:0
			}
			const tWidth = parseFloat(this.props.style.flexBasis)|| 0 //this.el.getBoundingClientRect().width			
			const {msWidth,fits,msHeightF} = calcMsOpts(this.el)			
			
			const msHeight = msHeightF()
			const shouldBurger = !fits(tWidth)
			const oneLine = !shouldBurger?Array.from(this.el.querySelectorAll(".cButton")).reduce((a,e)=>(a+e.getBoundingClientRect().width),0)+msWidth<=tWidth:false
			//log(`${tWidth - bWidth},${msWidth},${shouldBurger}`)
			if(this.state.shouldBurger != shouldBurger || this.state.msHeight != msHeight || this.state.oneLine!=oneLine)
				this.setState({shouldBurger,msHeight,oneLine})
		}
		componentDidUpdate(){
			this.calc()
		}
		componentDidMount(){
			this.calc()
		}
		componentWillUnmount(){
			//checkActivateCalls.remove(this.calc)
		}
		onRef(ref){
			this.el = ref
		}
		openBurger(e){
			this.setState({show:!this.state.show})
		}
		parentEl(node){
			if(!this.el||!node) return true
			let p = node
			while(p && p !=this.el){
				p = p.parentElement
				if(p == this.el) return true
			}
			return false
		}
		onBurgerBlur(e){
			if(this.state.show) this.openBurger(e)
		}
		onClick(e){
			if(!this.state.show) return
			const k = e.nativeEvent.path.find(_=>_.className.includes("msOpt")||_.className.includes("msBOpt"))
			this.onBurgerBlur(e)
		}
		render(){
			const {className,style,children} = this.props
			const buttons = children.filter(_=>_.props.at.className.includes("cButton") && !_.props.at.className.includes("rightMost"))
			const buttonsRight  = children.filter(_=>_.props.at.className.includes("cButton") && _.props.at.className.includes("rightMost"))
			const msOpts = children.filter(_=>_.props.at.className.includes("msOpt"))
			const msBOpts = children.filter(_=>_.props.at.className.includes("msBOpt"))
			const popupPostSt = className.includes("w1")?{left:"0px"}:{right:"0px"}
			const burger = this.state.shouldBurger?$("div",{onBlur:this.onBurgerBlur,onClick:this.onClick,tabIndex:"0",key:"burger",className:"burger",style:{position:"relative",outline:"none",padding:"0.3em"}},[
				$(MenuBurger,{key:"burgerButton",style:{color:"black"},className:"cButton",isBurgerOpen:this.state.show,onClick:this.openBurger}),
				$("div",{key:"burgerWrapper",style:{border:"1px solid #2196f3",position:"absolute",zIndex:"600",backgroundColor:"white",...popupPostSt,display:!this.state.show?"none":""}},msBOpts)				
			]):null
			const msOptsLine = !this.state.shouldBurger?msOpts:null
			const drStyle = className.includes("w1")?{justifyContent:"flex-end"}:{}			
			return $("div",{className,style:{...style,position:"relative"},ref:this.onRef, "data-burger":this.state.shouldBurger},[
				$("div",{key:"buttons",style:{display:"flex",...drStyle}},[buttons,burger,this.state.oneLine?$("div",{key:"oneLine",style:{marginTop:"1mm",display:"flex"}},msOptsLine):null,buttonsRight]),
				!this.state.oneLine?$("div",{key:"msOpts",style:{display:"flex", overflow:"hidden",position:"absolute",width:"100%",...drStyle}},msOptsLine):null,
				!this.state.oneLine&&!this.state.shouldBurger?$("div",{key:"heightFix",style:{height:this.state.msHeight+"px"}}):null,
				this.state.shouldBurger?$("div",{key:"hidden",style:{position:"absolute",zIndex:"-1",visibility:"hidden"}},msOpts):null
			])
		}
	}
	
	class TableOptsElement extends StatefulComponent{		
		getFilters(){
			return this.props.children.filter(c=>c.props.at.className == "filter")
		}		
		getWN(n){
			return this.props.children.filter(c=>c.props.at.className.split(' ').includes("w"+n))
		}
		getMaxWWidth(num,fAMapLength){
			if(!this["w"+num]) return 0
			const a = Array.from(this["w"+num].querySelectorAll(".msOpt"))
			const min = this.getMinWWidth(num,fAMapLength)
			if(a.length>0) {
				const b = a.reduce((a,e)=>a+e.getBoundingClientRect().width,0)
				if(fAMapLength==0) return min+b
				if(min<b) return b				
			}
			return min
		}
		getMinWWidth(num){
			if(!this["w"+num]) return 0
			const a = Array.from(this["w"+num].querySelectorAll(".cButton"))			
			const min = parseFloat(this.props.wMinWidth)*this.px2em	
			const {fits} = calcMsOpts(this["w"+num])			
			if(a.length>0) {
				const ww = a.reduce((a,e)=>a+e.getBoundingClientRect().width,0)
				const shouldBurger = !fits(ww)
				return ww + (shouldBurger?2*this.px2em:0)
			}
			return min
		}
		calc(){
			this.px2em = this.remRef.getBoundingClientRect().height
			if(this.px2em == 0) return
			let fMap 
			if(!this.props.hide)
				fMap = this.getFilters().map(f=>({active:f.props.at.active=="true"?true:false,o:f,basis:parseFloat(f.props.at.style.flexBasis)*this.px2em}))
			else 
				fMap = []
			let fAMap = this.props.open?(fMap.forEach(_=>_.active=true),fMap):fMap.filter(_=>_.active)
			fAMap = fAMap.length ==0 && fMap.length>0? (fMap[0].active=true,[fMap[0]]):fAMap 
			const tWidth = this.el.getBoundingClientRect().width*0.95
			const w1MinWWidth = this.getMinWWidth(1)
			const w2MinWWidth = this.getMinWWidth(2)
			const w1MaxWWidth = this.getMaxWWidth(1,fAMap.length)
			const w2MaxWWidth = this.getMaxWWidth(2,fAMap.length)
			const w3MinWWidth = this.getMinWWidth(3)
			const fWidth = tWidth - w1MinWWidth - w2MinWWidth - w3MinWWidth
		    
			const lines = calcLines(fAMap,fWidth)
			const optimized = optimizeByWidth(lines)
			const fMinWidth = calcMinWidth(optimized)
			const w1Width = (tWidth - w3MinWWidth - fMinWidth - w2MinWWidth > w1MaxWWidth)? w1MaxWWidth : w1MinWWidth
			const w2Width = (tWidth - w3MinWWidth - fMinWidth - w1Width > w2MaxWWidth)? w2MaxWWidth : w2MinWWidth
			
			const finalFWidth = tWidth - w1Width - w2Width - w3MinWWidth
			
			enableWhileFit(fMap,fAMap,optimized,finalFWidth)
			this.fMap = fMap
			const fMapChanged = Array.isArray(this.state.fMap)&& ((this.state.fMap.length!=fMap.length) || (this.state.fMap.length == fMap.length && !this.state.fMap.every((e,i)=>fMap[i].active == e.active)))
			if(this.state.finalFWidth != finalFWidth || this.state.w1Width != w1Width || this.state.w2Width != w2Width || fMapChanged)
				this.setState({show:true,finalFWidth, w1Width,w2Width,fMap})			
		}
		componentDidMount(){
			checkActivateCalls.add(this.calc)			
		}		
		componentWillUnmount(){
			checkActivateCalls.remove(this.calc)
		}
		showMinimalFilters(wn1,wn2){			
			const f = this.fMap.filter(_=>_.active).map(_=>_.o)
			if(f.length==0 && this.state.finalFWidth && !wn1.concat(wn2).some(_=>_.props.at.className.split(' ').includes("msOpt"))){
				let fw = this.state.finalFWidth
				let done = false
				const a = this.getFilters().filter((_,i)=>{
					if(done) return false
					const w = parseFloat(_.props.at.style.flexBasis)*this.px2em
					if(fw - w>0 || i==0) {fw-=w; return true}
					done = true
					return false
				})
				return a.map(_=>_.key)
			}		
			return f.map(_=>_.key)
		}
		render(){			
			const wn1 = this.getWN(1)
			const wn2 = this.getWN(2)
			const wn3 = this.getWN(3)
			const fl = this.fMap&&!this.props.open?this.showMinimalFilters(wn1,wn2):null					
			const style = {
				display:"flex",
				flex:"1 1",
				backgroundColor: "#c0ced8",				
				visibility:this.state.show?"visible":"hidden",
				height:this.state.show?"auto":"2em",
				...this.props.style
			}			
			const fstyle = {
				flex: "1 1 auto",
				flexBasis:this.state.finalFWidth?this.state.finalFWidth+"px":"auto",
				display:"flex",
				flexWrap:"wrap"				
			}
			const w1style = {
				flexBasis:this.state.w1Width?this.state.w1Width+"px":"auto"				
			}
			const w2style = {
				flexBasis:this.state.w2Width?this.state.w2Width+"px":"auto"
			}			
			const w3style = {}
			return $("div",{className:"tableOpts", ref:ref=>this.el=ref, style},[
			    $("div",{key:"remRef",ref:ref=>this.remRef=ref,style:{height:"1em",position:"absolute",zIndex:"-1"}}),
				$(filters,{key:1,style:fstyle,keys:fl},this.getFilters()),
				(wn1.length>0?$(wn,{key:2,className:"w1 z",style:w1style,ref:ref=>this.w1=ref?ref.el:null},wn1):null),
				(wn3.length>0?$(wn,{key:"tx",className:"w3 z",style:w3style,ref:ref=>this.w3=ref?ref.el:null},wn3):null),
				(wn2.length>0?$(wn,{key:3,className:"w2 z",style:w2style,ref:ref=>this.w2=ref?ref.el:null},wn2):null)
			])
		}
	}
	const transforms= {
		tp:{
			TableOptsElement
		}	
	}
	const checkActivate = checkActivateCalls.check	
	return ({transforms,checkActivate})
}
