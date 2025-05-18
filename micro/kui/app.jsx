
import React from "react"
import {useState,useEffect} from "react"
import {createRoot} from "react-dom/client"

export const PodDashboard = ({ loading, mail, pods, selectPod, restartPod }) => {
  return (
    <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
      <div className="w-full max-w-5xl">

        <div className="flex justify-end items-center space-x-4">
          <h1 className="text-xl font-semibold">{mail}</h1>
          <button className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white">Logout</button>
          <div className={`${loading ? "animate-spin" : ""} rounded-full h-6 w-6 border-t-2 border-b-2 border-white`}></div>
        </div>

        <title>Pods</title>
          <div className="overflow-x-auto rounded-t-md bg-gray-800">
            <table className="min-w-full text-white rounded-b-md">
              <thead>
                <tr>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Context</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">S</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Pod</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Status</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Creation Time</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Restart Count</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Actions</th>
                </tr>
              </thead>
              <tbody>
                {pods.map((pod, index) => (
                  <tr key={pod.key} className={`border-b border-gray-700 hover:bg-gray-700 cursor-pointer ${index % 2 !== 0 ? 'bg-gray-800' : 'bg-gray-900'}`}>
                    <td className="py-2 px-4">{pod.kube_context}</td>
                    <td className="py-2 px-4" onClick={selectPod(pod)}>{pod.selected ? '✔️' : ''}</td>
                    <td className="py-2 px-4" onClick={selectPod(pod)}>{pod.name}</td>
                    <td className="py-2 px-4">{pod.status}</td>
                    <td className="py-2 px-4">{pod.ctime}</td>
                    <td className="py-2 px-4">{pod.restarts}</td>
                    <td className="py-2 px-4">
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400" onClick={restartPod(pod)}>
                        Restart
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

      </div>
    </div>
  );
};

const post = async (msg, setState) => {
    try {
        setState(was => ({...was, loading: was.loading + 1}))
        const headers = { 'Content-Type': 'application/json' }
        const response = await fetch('/kop', { method: 'POST', headers, body: JSON.stringify(msg) })
        if(!response.ok) throw new Error(`${response.url} | ${response.status}`)
        return await response.json()
    } finally {
        setState(was => ({...was, loading: was.loading - 1}))
    }
}

const postAndRefresh = async (msg, setState) => {
    await post(msg, setState)
    await fetchIfVisible(setState)
}
const fetchIfVisible = async (setState) => {
    if(document.visibilityState === 'visible'){
        const data = await post({ op: 'get_state' }, setState)
        setState(was => ({...was, ...data}))
    }
}
const manageEventListener = (element, evName, listener) => {
    element.addEventListener(evName, listener)
    return () => element.removeEventListener(evName, listener)
}

const managePeriodicDataFetch = setState => {
    fetchIfVisible(setState);
    const interval = setInterval(() => fetchIfVisible(setState), 2000)
    const rmListener = manageEventListener(document, 'visibilitychange', () => fetchIfVisible(setState))
    return () => {
      clearInterval(interval)
      rmListener()
    }
}

const initState = () => ({loading: 0, userName: "", pods: []})
const App = () => {
    const [state, setState] = useState(initState)
    const handlers = { // cat become memo/cache later
        selectPod: pod => () => postAndRefresh({ op: 'select_pod', kube_context: pod.kube_context, name: pod.name }, setState),
        restartPod: pod => () => postAndRefresh({ op: 'restart_pod', kube_context: pod.kube_context, name: pod.name }, setState),
    }
    useEffect(() => managePeriodicDataFetch(setState), [setState])
    return <PodDashboard {...state} {...handlers}/>
}

const main= () => {
    const rootNativeElement = document.createElement("span")
    document.body.appendChild(rootNativeElement)
    createRoot(rootNativeElement).render(<App/>)
}

main()

//<meta name="viewport" content="width=device-width, initial-scale=1.0"/>