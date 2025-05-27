
import React from "react"
import {start} from "./util.js"

export const PodDashboard = ({
    processing, mail, userAbbr,
    clusters, lastCluster, showAllClusters,
    tab,
    pods, podNameLike,
    cio_tasks,
    willSend, willNavigate
}) => (
    <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
      <div className="w-full max-w-7xl">

        <div className="mb-4 flex justify-between items-start">
            <div className="flex justify-start items-center flex-wrap gap-2">
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

            <div className="flex justify-end items-center gap-4">
              <h1 className="text-xl font-semibold">{mail}</h1>
              <a className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white" href="/oauth2/sign_out">Logout</a>
              <div className={`${processing ? "animate-spin" : ""} rounded-full h-6 w-6 border-t-2 border-b-2 border-white`}></div>
            </div>
        </div>

        <div className="border-b border-gray-700 mb-4">
          <nav className="flex space-x-4 text-gray-300">
            {[["","Pods"],["cio","CIO"]].map(([key,hint]) => (
                <button key={key}
                  onClick={willNavigate({tab: key})}
                  className={`px-3 py-2 rounded-t-md ${(tab??'') === key ? 'bg-gray-800 text-white' : 'hover:bg-gray-700'}`}
                >{hint}</button>
            ))}
          </nav>
        </div>

        {(tab??'') === '' && <>

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
                ))
            }
          </div>

          <Table>
            <thead>
                <tr>
                  <Th>Context</Th><Th>S</Th><Th>Pod / Tag</Th>
                  <Th>Status</Th><Th>Creation / Start Time</Th><Th>Restarts</Th><Th>Actions</Th>
                </tr>
            </thead>
            <tbody>
                { pods.length===0 && <Tr><Td colSpan="7">Not found</Td></Tr> }
                { pods.map((pod, index) => <Tr key={pod.key} index={index}>
                    <Td>{pod.kube_context}</Td>
                    <Td>
                        <input type="radio" checked={pod.selected /*'✔️'*/}
                            onChange={willSend({ op: 'kop-select-pod', kube_context: pod.kube_context, name: pod.name })}
                        />
                    </Td>
                    <Td>{pod.host ? <a href={`https://${pod.host}`}>{pod.name}</a>: pod.name}<br/>{pod.image && pod.image.split(":").at(-1)}</Td>
                    <Td>{pod.status}{pod.ready && <div>ready</div>}</Td>
                    <Td>{pod.creationTimestamp} <br/> {pod.startedAt}</Td>
                    <Td>{pod.restarts}</Td>
                    <Td>
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'kop-recreate-pod', kube_context: pod.kube_context, name: pod.name })}
                      >Recreate</button>
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'kop-scale-down', kube_context: pod.kube_context, pod_name: pod.name })}
                      >Down</button>
                    </Td>
                </Tr>) }
            </tbody>
          </Table>

        </>}

        {(tab??'') === 'cio' && <>

          <Table>
            <thead>
              <tr>
                <Th>Context</Th><Th>Status</Th><Th>Queue</Th><Th>Task</Th>
              </tr>
            </thead>
            <tbody>
                { cio_tasks.length===0 && [<Tr><Td colSpan="4">Not found</Td></Tr>] }
                { cio_tasks.map((t, index) => <Tr key={t.task_name} index={index}>
                    <Td>{t.kube_context}</Td><Td>{t.status}</Td><Td>{t.queue_name}</Td><Td>{t.task_name}</Td>
                </Tr>)}
            </tbody>
          </Table>

        </>}
      </div>
    </div>
)

const Th = ({children}) => <th className="py-2 px-4 border-b border-gray-700 text-left">{children}</th>
const Td = props => <td className="py-2 px-4 space-x-2 space-y-2" {...props}/>
const Tr = ({index,...props}) => <tr className={`border-b border-gray-700 hover:bg-gray-700 ${(index??0) % 2 !== 0 ? 'bg-gray-800' : 'bg-gray-900'}`} {...props}/>
const Table = ({children}) => (
    <div className="overflow-x-auto rounded-t-md bg-gray-800">
        <table className="w-full sm:min-w-full lg:min-w-[1100px] text-white rounded-b-md">{children}</table>
    </div>
)

start("/kop", props => <PodDashboard {...props} lastCluster={props.last_cluster}/>)
