
import React from "react"
import {useState,useEffect} from "react"
import {createRoot} from "react-dom/client"

export const PodDashboard = ({ loading, mail, userAbbr, pods, clusters, selectPod, recreatePod, lastCluster, podNameLike, setPodNameLike }) => {
  const [showAllClusters,setShowAllClusters] = useState(false)
  return (
    <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
      <div className="w-full max-w-5xl">

        <div className="flex justify-end items-center space-x-4 my-4">
          <h1 className="text-xl font-semibold">{mail}</h1>
          <a className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white" href="/oauth2/sign_out">Logout</a>
          <div className={`${loading ? "animate-spin" : ""} rounded-full h-6 w-6 border-t-2 border-b-2 border-white`}></div>
        </div>

        <div className="mb-4 flex flex-wrap gap-2 justify-start">
          {clusters.map((c) => (
            (showAllClusters || c.watch) &&
            <a key={c.name} href={`/ind-login?name=${c.name}`}
              className={`px-3 py-1 rounded-full text-sm border whitespace-nowrap ${
                  lastCluster === c.name ? "bg-blue-600 border-blue-400":"bg-gray-700 border-gray-600"
              }`}
            >
              {c.name}
            </a>
          ))}
          <button onClick={() => setShowAllClusters(was=>!was)} className="text-sm text-blue-400 hover:underline">
            {showAllClusters ? 'Show less clusters for auth' : '... Show all clusters for auth'}
          </button>
        </div>

        <div className="mb-4 flex flex-wrap gap-2 justify-start">
            {
                [
                    { key: "", hint: `%${userAbbr}% pods` },
                    { key: "test", hint: "test pods" },
                    { key: "all", hint: "all pods" },
                ].map(({key,hint}) => (
                <button key={`k-${key}`} onClick={setPodNameLike(key)}
                  className={`px-3 py-1 rounded-full text-sm border whitespace-nowrap ${
                      (podNameLike??'') == key ? "bg-blue-600 border-blue-400":"bg-gray-700 border-gray-600"
                  }`}
                >{hint}</button>
            ))}
        </div>

          <div className="overflow-x-auto rounded-t-md bg-gray-800">
            <table className="min-w-full text-white rounded-b-md">
              <thead>
                <tr>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Context</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">S</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Pod</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Status</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Creation / Start Time</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Restarts</th>
                  <th className="py-2 px-4 border-b border-gray-700 text-left">Actions</th>
                </tr>
              </thead>
              <tbody>
                {pods.length > 0 ? null : <td colspan="7" className="py-2 px-4">No pods found</td>}
                {pods.map((pod, index) => (
                  <tr key={pod.key} className={`border-b border-gray-700 hover:bg-gray-700 cursor-pointer ${index % 2 !== 0 ? 'bg-gray-800' : 'bg-gray-900'}`}>
                    <td className="py-2 px-4">{pod.kube_context}</td>
                    <td className="py-2 px-4" onClick={selectPod(pod)}>{pod.selected ? '✔️' : ''}</td>
                    <td className="py-2 px-4" onClick={selectPod(pod)}>{pod.name}</td>
                    <td className="py-2 px-4">{pod.status}</td>
                    <td className="py-2 px-4">{pod.creationTimestamp} <br/> {pod.startedAt}</td>
                    <td className="py-2 px-4">{pod.restarts}</td>
                    <td className="py-2 px-4">
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400" onClick={recreatePod(pod)}>
                        Recreate
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

const post = async (op, args, setState) => {
    try {
        setState(was => ({...was, loading: was.loading + 1}))
        const response = await fetch(`/${op}?${new URLSearchParams(args).toString()}`, { method: 'POST' })
    } finally {
        setState(was => ({...was, loading: was.loading - 1}))
    }
}

const postAndRefresh = async (op, args, setState) => {
    await post(op, args, setState)
    await fetchIfVisible(setState)
}
const fetchIfVisible = async (setState) => {
    if(document.visibilityState === 'visible'){
        const args = { ...getHashParams(), time: Date.now() }
        const response = await fetch(`/kop-state?${new URLSearchParams(args).toString()}`)
        if(!response.ok) throw new Error(response.status)
        const data = await response.json()
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

const getHashParams = () => Object.fromEntries(new URLSearchParams(location.hash.substring(1)))
const setHashParamAndRefresh = async (k,v) => {
    location.hash = "#" + new URLSearchParams({...getHashParams(),[k]:v}).toString()
    await fetchIfVisible(setState)
}

const initState = () => ({loading: 0})
const App = () => {
    const [state, setState] = useState(initState)
    const handlers = { // cat become memo/cache later
        selectPod: pod => () => postAndRefresh('kop-select-pod', { kube_context: pod.kube_context, name: pod.name }, setState),
        recreatePod: pod => () => postAndRefresh('kop-recreate-pod', { kube_context: pod.kube_context, name: pod.name }, setState),
        setPodNameLike: text => () => setHashParamAndRefresh("podNameLike", text)
    }
    useEffect(() => managePeriodicDataFetch(setState), [setState])
    const hashParams = getHashParams()
    return state.mail && <PodDashboard {...state} {...hashParams} {...handlers} lastCluster={hashParams.last_cluster}/>
}

const main= () => {
    const rootNativeElement = document.createElement("span")
    document.body.appendChild(rootNativeElement)
    createRoot(rootNativeElement).render(<App/>)
}

main()

//<meta name="viewport" content="width=device-width, initial-scale=1.0"/>