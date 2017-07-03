export default function ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,document,scrollBy,eventManager}){
	let referenceCounter = 0;
	let isOn = false
	let interval = null
	const callbacks = [];
	const wifiCallbacks = [];
	const periodicCheck = () => {
		if(referenceCounter<=0) {
			if(isOn) {setScannerEnable(false)}
			if(interval) {
				clearInterval(interval);
				interval = null;
			}
		}		
	}	
	const scannerStatus = () => isOn
	const setScannerEnable = (value) => {isOn = value; if(isOn) Scanner&&Scanner.setScannerEnable(); else Scanner&&Scanner.setScannerDisable(); log(`scanner: set ${value}`)}
	const receiveAction = (barCode) => {Object.keys(callbacks).forEach(k=>callbacks[k]("barCode",barCode)); log(callbacks);}
	const reg = (obj) => {
		referenceCounter += 1;
		const key = Math.random();
		callbacks[key] = obj.callback;
		setScannerEnable(true);
		if(!interval) interval = setInterval(periodicCheck,2000);
		return () => {referenceCounter -= 1; delete callbacks[key];log("unreg");}
	}
	const moveScrollBy = (adj)=>{
		const maxHeight = document.querySelector("html").getBoundingClientRect().height
		const viewHeight = innerHeight()
		const fraction10 = (maxHeight - viewHeight)/10
		scrollBy(0,adj>0?fraction10:-fraction10)
	}
	const arrowBodyUP = ()=>{
		moveScrollBy(-10)
	}
	const arrowBodyDOWN = ()=>{
		moveScrollBy(10)
	}
	const fireGlobalEvent = (key) => {
		var event = eventManager.create("keydown",{key,bubbles:true})
		document.dispatchEvent(event)
	}
	const arrowUP = () => fireGlobalEvent("arrowUp")
	const arrowDOWN = () => fireGlobalEvent("arrrowDown")
	const arrowLEFT = () => fireGlobalEvent("arrowLeft")
	const arrowRIGHT = () => fireGlobalEvent("arrowRight")
	//const unReg = () => {referenceCounter -= 1; delete callbacks[obj];log("unreg");}
	const regWifi = (callback) => {
		wifiCallbacks.push(callback)		
		const unreg = () =>	{
			const index = wifiCallbacks.findIndex(wc=>wc == callback)
			wifiCallbacks.splice(index,1)
		}
		return {unreg}
	}
	const wifiLevel = (level) => {		
		wifiCallbacks.forEach(wc=>wc(level))
	} //level: 0-4
	const button = (color) => {
		//Object.keys(callbacks).forEach(k=>callbacks[k]("buttonColor",color));
		const buttonEl = document.querySelector(`.marker-${color}`)
		if(buttonEl) buttonEl.click()
	} //red/green
	return {scannerStatus,setScannerEnable,receiveAction,reg,arrowUP,arrowDOWN,arrowRIGHT,arrowLEFT,arrowBodyUP,arrowBodyDOWN,wifiLevel,button,regWifi}
}