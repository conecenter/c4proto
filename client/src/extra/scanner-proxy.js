export default function ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,document,scrollBy}){
	let referenceCounter = 0;
	let isOn = false
	let interval = null
	const callbacks = [];
	const periodicCheck = () => {
		if(referenceCounter<=0) {
			if(isOn) {setScannerEnable(false)}
			if(interval) {
				clearInterval(interval);
				interval = null;
			}
			return;
		}		
	}	
	const scannerStatus = () => isOn
	const setScannerEnable = (value) => {isOn = value; if(isOn) Scanner&&Scanner.setScannerEnable(); else Scanner&&Scanner.setScannerDisable(); log(`scanner: set ${value}`)}
	const receiveAction = (barCode) => {Object.keys(callbacks).forEach(k=>callbacks[k](barCode)); log(callbacks);}
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
	const arrowUP = function(){}
	const arrowDOWN = function(){}
	const arrowLEFT = function(){}
	const arrowRIGHT = function(){}
	//const unReg = () => {referenceCounter -= 1; delete callbacks[obj];log("unreg");}
	return {scannerStatus,setScannerEnable,receiveAction,reg,arrowUP,arrowDOWN,arrowRIGHT,arrowLEFT,arrowBodyUP,arrowBodyDOWN}
}