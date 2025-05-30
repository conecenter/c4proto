
import React from "react"
import {start,useSimpleInput,useTabs} from "./util.js"

export const Page = viewProps => {
    const {
      processing, mail, appVersion, viewTime, clusters, lastCluster, showAllClusters, tab, willNavigate
    } = viewProps

    const tabs = [
        { key: "pods", hint: "Pods", view: p => <PodsTabView {...p}/> },
        { key: "cio_tasks", hint: "CIO tasks", view: p => <CIOTasksTabView {...p}/> },
        { key: "cio_logs", hint: "CIO logs", view: p => <CIOLogsTabView {...p}/> },
    ]

    return (
        <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
          <div className="w-full max-w-7xl">

            {
                appVersion !== c4appVersion && <div className="fixed top-4 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white shadow-lg rounded-xl px-6 py-4 z-50 flex items-center space-x-4 animate-fadeIn border border-gray-700">
                  <span className="text-sm">
                    A new version is available.
                  </span>
                  <button
                    onClick={ev=>location.reload()}
                    className="bg-blue-600 hover:bg-blue-500 text-white text-sm font-medium py-1 px-3 rounded-lg"
                  >
                    Reload
                  </button>
                </div>
            }

            <div className="mb-4 flex justify-between items-start">
                <div className="flex justify-start items-center flex-wrap gap-2">
                  {(clusters??[]).map((c) => (
                    (showAllClusters || c.watch) &&
                    <a key={c.name} href={`/ind-login?${new URLSearchParams({name:c.name}).toString()}`}
                      className={roundedFull(lastCluster === c.name)}
                    >{c.name}</a>
                  ))}
                  <button onClick={willNavigate({showAllClusters: showAllClusters ? "":"1"})} className="text-sm text-blue-400 hover:underline">
                    {showAllClusters ? 'Show less clusters for auth' : '... Show all clusters for auth'}
                  </button>
                </div>

                <div className="flex justify-end items-center gap-4">
                  {viewTime && <div className="text-xs text-gray-400 ml-4">{`${Math.round(viewTime * 1000)}ms`}</div>}
                  <h1 className="text-xl font-semibold">{mail}</h1>
                  <a className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white" href="/oauth2/sign_out">Logout</a>
                  <div className={`${processing ? "animate-spin" : ""} rounded-full h-6 w-6 border-t-2 border-b-2 border-white`}></div>
                </div>
            </div>

            <div className="border-b border-gray-700 mb-4">
              <nav className="flex space-x-4 text-gray-300">
                {tabs.map(({key,hint}) => (
                    <button key={key}
                      onClick={willNavigate({tab: key})}
                      className={`px-3 py-2 rounded-t-md ${(tab??'') === key ? 'bg-gray-800 text-white' : 'hover:bg-gray-700'}`}
                    >{hint}</button>
                ))}
              </nav>
            </div>

            {useTabs({viewProps,tabs})}

          </div>
        </div>
    )
}

const PodsTabView = viewProps => {
    const {userAbbr, items, pod_name_like, willSend} = viewProps
    return <>
          <div className="mb-4 flex flex-wrap gap-2 justify-start">
              <SelectorFilterGroup viewProps={viewProps} fieldName="pod_name_like" items={[
                { key: `^(de|sp)-u?${userAbbr}.*-main-`, hint: `${userAbbr} pods` },
                { key: "^sp-.*test[0-9]+-.*-main-|-cio-", hint: "test pods" },
                { key: ".", hint: "all pods" },
              ]}/>
              <SimpleFilterInput viewProps={viewProps} fieldName="pod_name_like" placeholder="Filter pods..."/>
          </div>

          <Table>
            <thead>
                <tr>
                  <Th>Context<br/>Node</Th><Th>S</Th><Th>Pod<br/>Image tag</Th>
                  <Th>Status</Th><Th>Created at<br/>Started at</Th><Th>Restarts</Th><Th>Actions</Th>
                </tr>
            </thead>
            <tbody>
                <NotFoundTr viewProps={viewProps} colSpan="7"/>
                { items?.map((pod, index) => <Tr key={pod.key} index={index}>
                    <Td>
                        {pod.kube_context} <br/>
                        {pod.nodeName?.length <= 7 ? pod.nodeName : `${pod.nodeName.substring(0,7)}…`}
                    </Td>
                    <Td>
                        <input type="radio" checked={pod.selected /*'✔️'*/}
                            onChange={willSend({ op: 'kop-select-pod', kube_context: pod.kube_context, name: pod.name })}
                        />
                    </Td>
                    <Td>
                        {(() => {
                            const parts = pod.name.split('-')
                            const name = (
                              (parts[0] === 'de' || parts[0] === 'sp') && parts.length > 4
                            ) ? (() => {
                              const before = parts.slice(0, 2).join('-')
                              const emphasized = parts.slice(2, 4).join('-')
                              const after = parts.slice(4).join('-')
                              return (
                                <span className="text-sm text-gray-300">
                                  {before}-<span className="font-semibold text-yellow-400">{emphasized}</span>-{after}
                                </span>
                              )
                            })() : (
                              <span className="text-sm text-gray-300">{pod.name}</span>
                            )
                            return pod.host ? <a href={`https://${pod.host}`}>{name}</a>: name
                        })()} <br/>
                        {pod.image && pod.image.split(":").at(-1)}
                    </Td>
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

          {(()=>{
              const kube_context = items?.find(p => p.selected)?.kube_context
              return kube_context && userAbbr && <pre>{`
                  # operate selected:
                  kc ${kube_context} logs svc/fu-${userAbbr} -f --timestamps | grep ...
                  kc ${kube_context} exec -it svc/fu-${userAbbr} -- bash
              `}</pre>
          })()}
    </>
}

const CIOTasksTabView = viewProps => {
    const {items, managedKubeContexts} = viewProps
    return <>
          <div className="mb-4">
              <SelectorFilterGroup viewProps={viewProps} fieldName="cio_kube_context" items={managedKubeContexts.map(key => ({key,hint:key}))}/>
          </div>
          <Table>
            <thead>
              <tr>
                <Th>Status</Th><Th>Queue</Th><Th>Task</Th>
              </tr>
            </thead>
            <tbody>
                <NotFoundTr viewProps={viewProps} colSpan="3"/>
                { items?.map((t, index) => <Tr key={t.task_name} index={index}>
                    <Td>{t.status}</Td><Td>{t.queue_name}</Td><Td>{t.task_name}</Td>
                </Tr>)}
            </tbody>
          </Table>
    </>
}

const CIOLogsTabView = viewProps => {
    const {items, managedKubeContexts, cio_log_scope} = viewProps
    return <>
          <div className="mb-4 flex flex-wrap gap-6 justify-start">
              <SelectorFilterGroup viewProps={viewProps} fieldName="cio_kube_context" items={managedKubeContexts.map(key => ({key,hint:key}))}/>
              <SelectorFilterGroup viewProps={viewProps} fieldName="cio_log_scope" items={[{key:"all_lines",hint:"all lines"},{key:"key_lines",hint:"key lines"}]}/>
              <SimpleFilterInput viewProps={viewProps} fieldName="cio_log_task_exclude" placeholder="Filter pods..."/>
          </div>
          <Table>
            <thead>
              <tr>
                <Th>Offset</Th><Th>Time</Th><Th>Task</Th>
                {cio_log_scope !== "all_lines" && <>
                    <Th>Status</Th>
                    <Th>Description</Th>
                </>}
                <Th>...</Th>
              </tr>
            </thead>
            <tbody>
                <NotFoundTr viewProps={viewProps} colSpan="4"/>
                { items?.map((t, index) => <Tr key={t.offset} index={index}>
                    <Td className="whitespace-nowrap">{t.offset}</Td>
                    <Td className="whitespace-nowrap">{t.time.split(".")[0]}</Td>
                    <Td className="whitespace-nowrap">{t.task_name}</Td>
                    {cio_log_scope !== "all_lines" && <>
                        <Td className="whitespace-nowrap">{t.status}</Td>
                        <Td className="whitespace-nowrap">{t.descr}</Td>
                    </>}
                    <Td className="whitespace-nowrap overflow-hidden text-ellipsis">{t?.args.length > 0 && JSON.stringify(t.args)}</Td>
                </Tr>)}
            </tbody>
          </Table>
    </>
}

const NotFoundTr = ({viewProps,...props}) => {
    const {items, need_filters} = viewProps
    return items?.length > 0 ? undefined : <Tr>
        <Td {...props}>{need_filters ? "Select more filters ..." : "Not found"}</Td>
    </Tr>
}
const roundedFull = selected => `px-3 py-1 rounded-full text-sm border whitespace-nowrap ${
    selected ? "bg-blue-600 border-blue-400":"bg-gray-700 border-gray-600"
}`
const SelectorFilterGroup = ({viewProps,fieldName,items}) => (
    <div className="flex flex-wrap gap-2 justify-start">{
        items.map(({key,hint}) => (
            <button key={key} onClick={viewProps.willNavigate({[fieldName]: key})}
                className={`px-3 py-1 rounded-full text-sm border whitespace-nowrap ${
                    (viewProps[fieldName]??"") === key ? "bg-blue-600 border-blue-400":"bg-gray-700 border-gray-600"
                }`}
            >{hint}</button>
        ))
    }</div>
)
const SimpleFilterInput = ({viewProps,fieldName,...props}) => <form noValidate onSubmit={e => e.preventDefault()}><input {...useSimpleInput({
    ...props, type: "text",
    className: "px-3 py-1 rounded-full text-sm border bg-gray-800 text-white border-gray-600 placeholder-gray-400",
    dirtyClassName: "outline outline-dashed outline-orange-400",
    value: viewProps[fieldName] ?? "", onChange: v => viewProps.willNavigate({ [fieldName]: v })(),
})}/></form>
const Th = ({children}) => <th className="py-2 px-4 border-b border-gray-700 text-left">{children}</th>
const Td = ({className,...props}) => <td {...props} className={`py-2 px-4 space-x-2 space-y-2 ${className??''}`}/>
const Tr = ({index,...props}) => <tr className={`border-b border-gray-700 hover:bg-gray-700 ${(index??0) % 2 !== 0 ? 'bg-gray-800' : 'bg-gray-900'}`} {...props}/>
const Table = ({children}) => (
    <div className="overflow-x-auto rounded-t-md bg-gray-800">
        <table className="w-full sm:min-w-full lg:min-w-[1100px] text-white rounded-b-md">{children}</table>
    </div>
)

start("/kop", props => <Page {...props} lastCluster={props.last_cluster}/>)
