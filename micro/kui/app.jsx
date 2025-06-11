
import React from "react"
import {start,useSimpleInput,useTabs,withHashParams} from "./util.js"

export const Page = viewProps => {
    const {
      processing, mail, appVersion, viewTime, clusters, lastCluster, showAllClusters, tab, willNavigate
    } = viewProps

    const tabs = [
        { key: "pods", hint: "Pods", view: p => <PodsTabView {...p}/> },
        { key: "cio_tasks", hint: "CIO tasks", view: p => <CIOTasksTabView {...p}/> },
        { key: "cio_logs", hint: "CIO logs", view: p => <CIOLogsTabView {...p}/> },
        { key: "s3", hint: "S3", view: p => <S3SnapshotsTabView {...p}/> },
        { key: "links", hint: "Links", view: p => <LinksTabView {...p}/> },
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
                    <a key={c.name} href={`/ind-login?${new URLSearchParams({
                        name: c.name, location_hash: withHashParams({last_cluster:c.name})
                    }).toString()}`} className={roundedFull(lastCluster === c.name)}>{c.name}</a>
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
                            onChange={willSend({ op: 'pods.select_pod', kube_context: pod.kube_context, name: pod.name })}
                        />
                    </Td>
                    <Td>
                      <div class="flex items-center gap-1">
                        {
                            pod.host && <a {...tBlank()} href={`https://${pod.host}`}>
                                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                    <path d="M21 11V3h-8v2h4v2h-2v2h-2v2h-2v2H9v2h2v-2h2v-2h2V9h2V7h2v4h2zM11 5H3v16h16v-8h-2v6H5V7h6V5z" fill="#FFFFFF"/>
                                </svg>
                            </a>
                        }{(() => {
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
                            return name
                        })()}
                      </div>
                      {pod.image && pod.image.split(":").at(-1)}
                    </Td>
                    <Td>{pod.status}{pod.ready && <div>ready</div>}</Td>
                    <Td>{pod.creationTimestamp} <br/> {pod.startedAt}</Td>
                    <Td>{pod.restarts}</Td>
                    <Td>
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'pods.recreate_pod', kube_context: pod.kube_context, name: pod.name })}
                      >Recreate</button>
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'pods.scale_down', kube_context: pod.kube_context, pod_name: pod.name })}
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

const formatLogSize = v => `${(v / 1024).toFixed(1)} KiB`

const CIOLogsTabView = viewProps => {
    const {
        all_log_sizes, cio_kube_context, cio_query,
        searching_size, search_result_code, search_result_size, result_page, willSend
    } = viewProps

    return (
        <div className="space-y-6 p-4 text-sm text-white">
            {/* Filter Controls */}
            <div className="space-y-3">
                <SelectorFilterGroup
                    viewProps={viewProps}
                    fieldName="cio_kube_context"
                    items={(all_log_sizes || []).map(c => ({
                        key: c.kube_context,
                        hint: `${c.kube_context} (${formatLogSize(c.log_size)})`
                    }))}
                />
                <div className="flex gap-2">
                    <SimpleFilterInput
                        viewProps={viewProps}
                        fieldName="cio_query"
                        placeholder="Search query..."
                    />
                    <button
                        onClick={willSend({
                            op: 'cio_logs.search',
                            kube_context: cio_kube_context,
                            query: cio_query
                        })}
                        className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                    >
                        Search
                    </button>
                </div>
                <div className="text-gray-400 space-y-1">
                    {
                        search_result_code > 1 ? <p>Search error</p> :
                        search_result_code === 1 ? <p>Not found</p> :
                        search_result_code === 0 ?
                            <a
                                className="underline hover:text-blue-400"
                                href={`/cio-log-search-download?time=${Date.now()}`}
                                {...tBlank()}
                            >
                                Found {formatLogSize(search_result_size)} — Download result
                            </a> :
                        Number.isInteger(searching_size) ?
                            <p>Searching… <span className="text-white">{formatLogSize(searching_size)}</span></p> :
                        undefined
                    }
                </div>
            </div>

            {/* Log Results */}
            {result_page && (
                <div className="space-y-4">
                    <div className="flex items-center gap-4">
                        <button
                            onClick={willSend({ op: "cio_logs.goto_page", page: result_page.page - 1 })}
                            className="px-3 py-1 rounded border border-gray-600 hover:bg-gray-700"
                            disabled={result_page.page <= 0}
                        >
                            &larr; Later
                        </button>
                        <span className="text-gray-300">Page {result_page.page}</span>
                        <button
                            onClick={willSend({ op: "cio_logs.goto_page", page: result_page.page + 1 })}
                            className="px-3 py-1 rounded border border-gray-600 hover:bg-gray-700"
                        >
                            Earlier &rarr;
                        </button>
                    </div>
                    <pre className="bg-gray-900 text-white p-4 rounded-lg overflow-auto max-h-[60vh] border border-gray-700">
                        {result_page.lines.join("\n")}
                    </pre>
                </div>
            )}
        </div>
    )
}

const formatS3Size = v => `${(v / 1024 / 1024).toFixed(1)} MiB`;
const S3SnapshotsTabView = viewProps => {
    const {items, s3contexts, s3context, willSend} = viewProps
    return (
        <>
            <div className="flex gap-2 mb-4">
                <SelectorFilterGroup
                    viewProps={viewProps}
                    fieldName="s3context"
                    items={(s3contexts||[]).map(key => ({ key, hint: key }))}
                />
                <button
                    onClick={willSend({ op: 's3.search', s3context })}
                    className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                >
                    Search
                </button>
            </div>
            <Table>
                <thead>
                    <tr>
                        <Th>Bucket</Th>
                        <Th className="text-right">Objects</Th>
                        <Th className="text-right">Size</Th>
                        <Th>Last Key</Th>
                        <Th className="text-right">Last Size</Th>
                        <Th>Last Modified</Th>
                    </tr>
                </thead>
                <tbody>
                    <NotFoundTr viewProps={viewProps} colSpan="6" />
                    {items?.map((b, index) => (
                        <Tr key={b.bucket_name} index={index}>
                            <Td>{b.bucket_name}</Td>
                            <Td className="text-right">{b.is_truncated?">":""}{b.objects_count}</Td>
                            <Td className="text-right">{b.is_truncated?">":""}{formatS3Size(b.objects_size)}</Td>
                            <Td>{b.last_obj_key ? `${b.last_obj_key.split("-")[0]}…` : "-"}</Td>
                            <Td className="text-right">{b.last_obj_size ? formatS3Size(b.last_obj_size) : ""}</Td>
                            <Td>{b.last_obj_mod_time ? b.last_obj_mod_time.split(".")[0] : "-"}</Td>
                        </Tr>
                    ))}
                </tbody>
            </Table>
        </>
    )
}

const LinksTabView = ({ cluster_links = [], custom_links = [] }) => {
    const groupedLinks = Object.groupBy(custom_links, link => link.group)

    return (
        <div className="space-y-8 p-4 text-sm text-white">
            {/* Cluster Links */}
            <section>
                <h2 className="text-lg font-semibold mb-2">Cluster Dashboards</h2>
                <ul className="grid sm:grid-cols-2 lg:grid-cols-3 gap-2">
                    {cluster_links.map(({ name, grafana }) => (
                        <li key={name}>
                            <a
                                href={`https://${grafana}/dashboards`}
                                {...tBlank()}
                                className="block px-4 py-2 rounded bg-blue-700 hover:bg-blue-600 text-white"
                            >
                                {name}
                            </a>
                        </li>
                    ))}
                </ul>
            </section>

            {/* Custom Links Grouped */}
            {Object.entries(groupedLinks).map(([group, links]) => (
                <section key={group}>
                    <h2 className="text-lg font-semibold mb-2 capitalize">{group}</h2>
                    <ul className="grid sm:grid-cols-2 lg:grid-cols-3 gap-2">
                        {links.map(({ name, url }) => (
                            <li key={name}>
                                <a
                                    href={url}
                                    {...tBlank()}
                                    className="block px-4 py-2 rounded bg-gray-700 hover:bg-gray-600 text-white"
                                >
                                    {name}
                                </a>
                            </li>
                        ))}
                    </ul>
                </section>
            ))}
        </div>
    )
}

const tBlank = () => ({ target: "_blank", rel: "noopener noreferrer" })

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
const Th = ({className,...props}) => <th {...props} className={`py-2 px-4 border-b border-gray-700 text-left ${className??''}`}/>
const Td = ({className,...props}) => <td {...props} className={`py-2 px-4 space-x-2 space-y-2 ${className??''}`}/>
const Tr = ({index,...props}) => <tr className={`border-b border-gray-700 hover:bg-gray-700 ${(index??0) % 2 !== 0 ? 'bg-gray-800' : 'bg-gray-900'}`} {...props}/>
const Table = ({children}) => (
    <div className="overflow-x-auto rounded-t-md bg-gray-800">
        <table className="w-full sm:min-w-full lg:min-w-[1100px] text-white rounded-b-md">{children}</table>
    </div>
)

start("/kop", props => <Page {...props} lastCluster={props.last_cluster}/>)
