import React from "react"
import {useState,useEffect,useMemo} from "react"
import {start,useSimpleInput,useTabs,withHashParams} from "./util.js"

const ReloadDialog = message => (
    <div className="fixed top-4 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white shadow-lg rounded-xl px-6 py-4 z-50 flex items-center space-x-4 animate-fadeIn border border-gray-700">
        <span className="text-sm">{message}</span>
        <button
            onClick={ev=>location.reload()}
            className="bg-blue-600 hover:bg-blue-500 text-white text-sm font-medium py-1 px-3 rounded-lg"
        >Reload</button>
    </div>
)

export const Page = viewProps => {
    const {
      processing, mail, appVersion, viewTime, clusters, lastCluster, showAllClusters, tab, willNavigate, connectionAttempts
    } = viewProps

    const tabs = [
        { key: "pods", hint: "Pods", view: p => <PodsTabView {...p}/> },
        { key: "cio_tasks", hint: "CIO tasks", view: p => <CIOTasksTabView {...p}/> },
        { key: "cio_events", hint: "CIO events", view: p => <CIOEventsTabView {...p}/> },
        { key: "cio_logs", hint: "CIO logs", view: p => <CIOLogsTabView {...p}/> },
        { key: "s3", hint: "S3", view: p => <S3SnapshotsTabView {...p}/> },
        { key: "profiling", hint: "Profiling", view: p => <ProfilingTabView {...p}/> },
        { key: "links", hint: "Links", view: p => <LinksTabView {...p}/> },
    ]

    return (
        <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
          <div className="w-full max-w-7xl">

            {
                appVersion !== c4appVersion ? ReloadDialog("A new version is available.") :
                connectionAttempts > 2 ? ReloadDialog("Connection problems.") :
                null
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

const compareBy = getKey => (a, b) => getKey(a).localeCompare(getKey(b))

const PodsTabView = viewProps => {
    const {userAbbr, items, pod_name_like, pod_contexts, sort_by_node, willSend, willNavigate} = viewProps
    const sortedItems = useMemo(() => sort_by_node ? items?.toSorted(compareBy(it => it.nodeName||"")) : items, [items, sort_by_node])
    return <>
          {pod_contexts && <div className="mb-4">
              <SelectorFilterGroup viewProps={viewProps} fieldName="pod_list_kube_context" items={pod_contexts.map(key => ({key,hint:key}))}/>
          </div>}

          <div className="mb-4 flex flex-wrap gap-2 justify-start">
              <SelectorFilterGroup viewProps={viewProps} fieldName="pod_name_like" items={[
                { key: `^(de|sp)-u?${userAbbr}.*-main-`, hint: `${userAbbr} pods` },
                { key: "^sp-.*test[0-9]+-.*-main-|-cio-", hint: "test pods" },
                { key: ".", hint: "all pods" },
              ]}/>
              <SimpleFilterInput viewProps={viewProps} fieldName="pod_name_like" placeholder="Filter pods..."/>
              <button onClick={willNavigate({sort_by_node: sort_by_node ? "" : "1"})}
                      className={`px-3 py-1 rounded-full text-sm border whitespace-nowrap ${
                          sort_by_node ? "bg-blue-600 border-blue-400" : "bg-gray-700 border-gray-600"
                      }`}>
                  🖥️ Group by node
              </button>
          </div>

          <Table>
            <thead>
                <tr>
                  <Th>Node</Th><Th>S</Th><Th>Pod<br/>Image tag</Th>
                  <Th>Status</Th><Th>Created at<br/>Started at</Th><Th>Restarts</Th><Th>Usage</Th><Th>Actions</Th>
                </tr>
            </thead>
            <tbody>
                <NotFoundTr viewProps={viewProps} colSpan="8"/>
                { sortedItems?.map((pod, index) => <Tr key={pod.key} index={index}>
                    <Td>
                        <TruncatedText text={pod.nodeName||"-"} startChars={7} align="left"/>
                    </Td>
                    <Td>
                        <input type="radio" checked={pod.selected /*'✔️'*/}
                            onChange={willSend({ op: 'pods.select_pod', kube_context: pod.kube_context, name: pod.name })}
                        />
                    </Td>
                    <Td>
                      <div class="flex items-center gap-1">
                        <button
                            onClick={willNavigate({
                                tab: 'profiling', profiling_pod_name: pod.name, profiling_kube_context: pod.kube_context
                            })}
                            className="p-1"
                            title="Profile this pod"
                        >
                            📊
                        </button>
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
                    <Td className="text-right font-mono text-xs">
                        {reformatTopCPU(pod.usage_cpu||'')}<br/>{reformatTopSize(pod.usage_memory||'')}
                    </Td>
                    <Td>
                      <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'pods.recreate_pod', kube_context: pod.kube_context, name: pod.name })}
                      >Recreate</button>
                      {pod.name.match(/^(de|sp)-/) && <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                        onClick={willSend({ op: 'pods.scale_down', kube_context: pod.kube_context, pod_name: pod.name })}
                      >Down</button>}
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

const reformatTopSize = v => (
    v.substring(v.length-2) === "Ki" ? `${(v.substring(0, v.length-2) / 1024 / 1024).toFixed(1)} GiB` :
    v.substring(v.length-2) === "Mi" ? `${(v.substring(0, v.length-2) / 1024 ).toFixed(1)} GiB` :
    v
)
const reformatTopCPU = v => v.substring(v.length-1) === "n" ? `${(v.substring(0, v.length-1) / 1024 / 1024)|0}m` : v

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

const CIOEventsTabView = viewProps => {
    const {items, willSend} = viewProps
    return <>
          <Table>
            <thead>
              <tr>
                <Th>Context</Th><Th>Task</Th><Th>Status</Th><Th>At</Th><Th>Actions</Th>
              </tr>
            </thead>
            <tbody>
                <NotFoundTr viewProps={viewProps} colSpan="4"/>
                { items?.map((t, index) => <Tr key={`${t.kube_context}/${t.task}`} index={index}>
                    <Td>{t.kube_context}</Td><Td>{t.task}</Td><Td>{t.status}</Td>
                    <Td>{new Date(t.at*1000).toISOString()}</Td>
                    <Td>
                        <button className="bg-yellow-500 text-black px-2 py-1 rounded hover:bg-yellow-400"
                            onClick={willSend({ op: 'cio_events.hide', kube_context: t.kube_context, task: t.task })}
                        >Hide</button>
                    </Td>
                </Tr>)}
            </tbody>
          </Table>
    </>
}

const formatLogSize = v => `${(v / 1024).toFixed(1)} KiB`

const CIOLogsTabView = viewProps => {
    const {
        all_log_sizes, cio_kube_context, cio_query, cio_context_lines, cio_page_lines,
        searching_size, search_result_code, search_result_size, result_page, result_page_count, willSend,
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
                        viewProps={viewProps} fieldName="cio_query" placeholder="Search query..."
                    />
                    <SimpleFilterInput
                        viewProps={viewProps} fieldName="cio_context_lines" placeholder="Context lines..."
                    />
                    <SimpleFilterInput
                        viewProps={viewProps} fieldName="cio_page_lines" placeholder="Lines per page..."
                    />
                    <button
                        onClick={willSend({
                            op: 'cio_logs.search', kube_context: cio_kube_context, query: cio_query,
                            context_lines: cio_context_lines || "0", page_lines: cio_page_lines || "20",
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
                        <span className="text-gray-300">Page {result_page.page} / {result_page_count}</span>
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
    const {items, reset_message, s3contexts, s3context, bucket_name_like, willSend} = viewProps
    return (
        <>
            <div className="flex gap-2 mb-4">
                <SelectorFilterGroup
                    viewProps={viewProps}
                    fieldName="s3context"
                    items={(s3contexts||[]).map(key => ({ key, hint: key }))}
                />
                <SimpleFilterInput viewProps={viewProps} fieldName="bucket_name_like" placeholder="Filter buckets..."/>
                <button
                    onClick={willSend({ op: 's3.search', s3context, bucket_name_like })}
                    className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                >Search</button>
            </div>

            {reset_message ? (
                <div className="bg-gray-800 border border-gray-600 rounded-lg p-6 text-center">
                    <p className="text-white text-lg mb-4">{reset_message}</p>
                    <p className="text-gray-400">Press "Search" to refresh bucket list</p>
                </div>
            ) : <Table>
                <thead>
                    <tr>
                        <Th>Bucket</Th>
                        <Th className="text-right">Objects</Th>
                        <Th className="text-right">Size</Th>
                        <Th>Last Key</Th>
                        <Th className="text-right">Last Size</Th>
                        <Th>Last Modified</Th>
                        <Th>Actions</Th>
                    </tr>
                </thead>
                <tbody>
                    <NotFoundTr viewProps={viewProps} colSpan="7" />
                    {items?.map((b, index) => (
                        <Tr key={b.bucket_name} index={index}>
                            <Td>{b.bucket_name}</Td>
                            <Td className="text-right">{b.is_truncated?">":""}{b.objects_count}</Td>
                            <Td className="text-right">{b.is_truncated?">":""}{formatS3Size(b.objects_size)}</Td>
                            <Td><TruncatedText text={b.last_obj_key||"-"} startChars={17} align="right"/></Td>
                            <Td className="text-right">{b.last_obj_size ? formatS3Size(b.last_obj_size) : ""}</Td>
                            <Td>{b.last_obj_mod_time ? b.last_obj_mod_time.split(".")[0] : "-"}</Td>
                            <Td>
                                {b.has_reset_file ? <span className="text-gray-400 text-sm">🔄 Reset pending</span> : (
                                    <button
                                        onClick={willSend({
                                            op: 's3.reset_bucket',
                                            s3context,
                                            bucket_name: b.bucket_name
                                        })}
                                        className="bg-red-600 hover:bg-red-500 text-white px-3 py-1 rounded text-sm"
                                        title="Schedule snapshot reset"
                                    >
                                        Reset
                                    </button>
                                )}
                            </Td>
                        </Tr>
                    ))}
                </tbody>
            </Table>}
        </>
    )
}

const ProfilingTabView = viewProps => {
    const {
        profiling_kube_context, profiling_pod_name, profiling_period,
        profiling_contexts, profiling_status, willSend
    } = viewProps
    const [seconds, setSeconds] = useState(0)
    useEffect(() => {
        setSeconds(0)
        if (profiling_status === "P") {
            const interval = setInterval(() => {
                setSeconds(prev => prev + 1)
            }, 1000)
            return () => clearInterval(interval)
        }
    }, [profiling_status])
    return !profiling_status ? <>
        <div className="flex gap-2 mb-4">
            <SelectorFilterGroup
                viewProps={viewProps}
                fieldName="profiling_kube_context"
                items={(profiling_contexts||[]).map(key => ({ key, hint: key }))}
            />
        </div>
        <div className="flex gap-2 mb-4">
            <SimpleFilterInput
                viewProps={viewProps}
                fieldName="profiling_pod_name"
                placeholder="Pod name..."
            />
            <SelectorFilterGroup
                viewProps={viewProps}
                fieldName="profiling_period"
                items={[{ key: "15", hint: "15s" }, { key: "", hint: "60s" }, { key: "300", hint: "300s" }]}
            />
            <button
                onClick={willSend({
                    op: 'profiling.profile',
                    kube_context: profiling_kube_context, pod_name: profiling_pod_name, period: profiling_period || "60"
                })}
                className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
            >
                Profile
            </button>
        </div>
    </> :
    <div className="flex gap-2 items-center text-gray-400">
        {
            profiling_status === "F" ? <>
                <p>Failed…</p>
                <button
                    onClick={willSend({ op: 'profiling.reset_status' })}
                    className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                >
                    Reset status
                </button>
            </> :
            profiling_status === "S" ?
                <a
                    className="underline hover:text-blue-400"
                    href={`/profiling-flamegraph.html?time=${Date.now()}`}
                    {...tBlank()}
                >
                    Download result
                </a> :
            profiling_status === "P" ? <p>Profiling… {seconds}s</p> :
            undefined
        }
    </div>
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

const TruncatedText = ({text, startChars, align}) => {
    const [isHovered, setIsHovered] = useState(false)
    const className = "font-mono text-sm"
    const truncated = `${text.substring(0, startChars)}…`
    return !text || text.length <= startChars ? <span className={className}>{text}</span> : (
        <span
            className={`relative cursor-help ${className}`}
            onMouseEnter={() => setIsHovered(true)}
            onMouseLeave={() => setIsHovered(false)}
        >
            {isHovered && (
                <div
                    className="absolute whitespace-nowrap bg-gray-800 text-white border border-gray-600 rounded px-2 py-1"
                    style={{[align]: "0"}}
                >{text}</div>
            )}
            {truncated}
        </span>
    )
}

start("/kop", props => <Page {...props} lastCluster={props.last_cluster}/>)