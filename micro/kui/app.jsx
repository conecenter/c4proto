
import React from "react"
import {start} from "./util.js"

export const PodDashboard = ({ processing, mail, userAbbr, pods, clusters, lastCluster, podNameLike, showAllClusters, willSend, willNavigate}) => {
  const willSelectPod = pod => willSend({ op: 'kop-select-pod', kube_context: pod.kube_context, name: pod.name })
  return (
    <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
      <div className="w-full max-w-5xl">

        <div className="flex justify-end items-center space-x-4 my-4">
          <h1 className="text-xl font-semibold">{mail}</h1>
          <a className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white" href="/oauth2/sign_out">Logout</a>
          <div className={`${processing ? "animate-spin" : ""} rounded-full h-6 w-6 border-t-2 border-b-2 border-white`}></div>
        </div>

        <div className="mb-4 flex flex-wrap gap-2 justify-start">
          {clusters.map((c) => (
            (showAllClusters || c.watch) &&
            <a key={c.name} href={`/ind-login?${new URLSearchParams({name:c.name}).toString()}`}
              className={`px-3 py-1 rounded-full text-sm border whitespace-nowrap ${
                  lastCluster === c.name ? "bg-blue-600 border-blue-400":"bg-gray-700 border-gray-600"
              }`}
            >
              {c.name}
            </a>
          ))}
          <button onClick={willNavigate({showAllClusters: showAllClusters ? "":"1"})} className="text-sm text-blue-400 hover:underline">
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
                <button key={`k-${key}`} onClick={willNavigate({podNameLike: key})}
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
                {pods.length > 0 ? null : <tr><td colSpan="7" className="py-2 px-4">No pods found</td></tr>}
                {pods.map((pod, index) => (
                  <tr key={pod.key} className={`border-b border-gray-700 hover:bg-gray-700 cursor-pointer ${index % 2 !== 0 ? 'bg-gray-800' : 'bg-gray-900'}`}>
                    <td className="py-2 px-4">{pod.kube_context}</td>
                    <td className="py-2 px-4" onClick={willSelectPod(pod)}>{pod.selected ? '✔️' : ''}</td>
                    <td className="py-2 px-4" onClick={willSelectPod(pod)}>{pod.name}</td>
                    <td className="py-2 px-4">{pod.status}</td>
                    <td className="py-2 px-4">{pod.creationTimestamp} <br/> {pod.startedAt}</td>
                    <td className="py-2 px-4">{pod.restarts}</td>
                    <td className="py-2 px-4">
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'kop-recreate-pod', kube_context: pod.kube_context, name: pod.name })}
                      >Recreate</button>
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'kop-scale-down', kube_context: pod.kube_context, pod_name: pod.name })}
                      >Down</button>
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

start("/kop", props => <PodDashboard {...props} lastCluster={props.last_cluster}/>)
