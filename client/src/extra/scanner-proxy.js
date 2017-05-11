export default function ScannerProxy({Scanner,setInterval,clearInterval,log}){
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
	const receiveAction = (barCode) => Object.keys(callbacks).forEach(k=>callbacks[k](barCode))
	const reg = (key,callback) => {referenceCounter += 1; callbacks[key] = callback; setScannerEnable(true); if(!interval) interval = setInterval(periodicCheck,2000);}
	const unReg = (key) => {referenceCounter -= 1; delete callbacks[key];}
	return {scannerStatus,setScannerEnable,receiveAction,reg,unReg}
}