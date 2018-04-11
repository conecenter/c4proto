export default function ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,documentManager,scrollBy,eventManager}){
	let referenceCounter = 0;
	let isOn = false
	let interval = null
	const {activeElement,document} = documentManager
	const callbacks = [];
	const wifiCallbacks = [];
	const periodicCheck = () => {
		if(referenceCounter<=0) {
			if(isOn) {setScannerEnable(false,isOn.scanMode)}
			if(interval) {
				clearInterval(interval);
				interval = null;
			}
		}		
	}	
	const scannerStatus = () => isOn
	const setScannerEnable = (value,scanMode) => {
       	if(value && isOn && isOn.scanMode!=scanMode) setScannerEnable(false,isOn.scanMode) 	
		isOn = value?{value,scanMode}:value;
		switch(scanMode){
			case "uhf": if(isOn) Scanner&&Scanner.setUHFenable(); else Scanner&&Scanner.setUHFdisable(); break;
			default: if(isOn) Scanner&&Scanner.setScannerEnable(); else Scanner&&Scanner.setScannerDisable();
		}			
		log(`scanner: set ${value} ${scanMode}`)
	}
	const receiveAction = (barCode) => {Object.keys(callbacks).forEach(k=>callbacks[k]("barCode",barCode)); log(callbacks);}
	const reg = (obj) => {
		referenceCounter += 1;
		const key = Math.random();
		callbacks[key] = obj.callback;
		setScannerEnable(true,obj.scanMode());
		if(!interval) interval = setInterval(periodicCheck,2000);
		const unreg = () => {
			referenceCounter -= 1;
			delete callbacks[key];
			log("unreg scanner");
		}
		const switchTo = scanMode => setScannerEnable(true,scanMode)		
		
		return {unreg,switchTo}
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
		activeElement().dispatchEvent(event)
	}
	const arrowUP = () => fireGlobalEvent("ArrowUp")
	const arrowDOWN = () => fireGlobalEvent("ArrowDown")
	const arrowLEFT = () => fireGlobalEvent("ArrowLeft")
	const arrowRIGHT = () => fireGlobalEvent("ArrowRight")
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