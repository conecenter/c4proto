import React from "react"
import {useState,useEffect,useMemo} from "react"
import {
    start, toPath, useAppMutation, useErrorList, useNavigation, usePending, useSimpleInput, withHashParams,
    queryOpt, useRQuery
} from "./util.js"

const ReloadDialog = message => (
    <div className="fixed inset-x-0 top-0 z-50 flex justify-center pointer-events-none">
        <div className="pointer-events-auto mt-6 mx-4 max-w-xl w-full bg-gradient-to-r from-amber-500 via-rose-600 to-red-600 text-white shadow-2xl rounded-2xl px-6 py-5 flex items-start gap-4 border border-white/30 backdrop-blur-sm">
            <div className="text-2xl leading-none" aria-hidden>⟳</div>
            <div className="flex-1">
                <div className="font-semibold text-lg">{message}</div>
                <div className="text-sm text-white/90 mt-1">Reload to pick up the latest changes and restore connectivity.</div>
            </div>
            <button
                onClick={ev=>location.reload()}
                className="bg-black/40 hover:bg-black/60 text-white text-sm font-semibold py-2 px-4 rounded-lg uppercase tracking-wide"
            >Reload</button>
        </div>
    </div>
)

const tabTitles = [
    { keys: ["pods","profiling"], hint: "Pods" },
    { keys: ["cio_tasks"], hint: "CIO tasks" },
    { keys: ["cio_events"], hint: "CIO events" },
    { keys: ["cio_logs"], hint: "CIO logs" },
    { keys: ["s3", "s3bucket"], hint: "S3" },
    { keys: ["allure"], hint: "Allure" },
    { keys: ["links"], hint: "Links" },
]

const opMessages = { // todo fix
    "pods.select_pod": "Failed to select pod for port-forward.",
    "pods.recreate_pod": "Failed to recreate pod.",
    "pods.scale_down": "Failed to scale deployment down.",
    "cio_tasks.kill": "Failed to kill task.",
    "cio_events.hide": "Failed to hide event.",
    "cio_logs.search": "Log search failed.",
    "s3.search": "Bucket search failed. Try again later.",
    "s3bucket.reset_bucket": "Failed to schedule reset.",
    "s3bucket.make_snapshot": "Failed to make snapshot.",
    "profiling.profile": "Failed to start profiling.",
    "profiling.thread_dump": "Failed to collect thread dump.",
    "profiling.load_logback": "Failed to load logback config.",
    "profiling.save_logback": "Failed to save logback config.",
    "profiling.unload_logback": "Failed to close logback editor.",
    "profiling.reset_profile_status": "Failed to clear profiling.",
    "profiling.reset_thread_status": "Failed to clear thread dump.",
    "profiling.enable_gc_log": "Failed to enable GC log.",
    "refresh": "Failed to refresh.",
}
const opOf = url => url.slice(1).split("?")[0] // mutation variable is the toPath URL; recover the op for messages

const staticLoad = queryOpt({ invalidationGroup: "static", interval:15 })
const rtLoad = queryOpt({ interval: 2 })
const rareLoad = queryOpt({ interval: 15 })

const views = [
    {
        op: "pods", view: p => <PodsTabView {...p}/>, loadOpt: rtLoad,
        args: ({pod_name_like, filter_kube_context: kube_context})=> ({pod_name_like, kube_context}),
    },
    {
        op: "cio_tasks", view: p => <CIOTasksTabView {...p}/>, loadOpt: rtLoad,
        args: ({cio_kube_context:kube_context})=> ({kube_context}),
    },
    {
        op: "cio_events", view: p => <CIOEventsTabView {...p}/>, loadOpt: rtLoad,
    },
    {
        op: "cio_logs", view: p => <CIOLogsTabView {...p}/>, loadOpt: rtLoad,
    },
    {
        op: "s3", view: p => <S3SnapshotsTabView {...p}/>, loadOpt: rtLoad,
        args: ({filter_kube_context: kube_context, bucket_name_like})=> ({kube_context, bucket_name_like}),
    },
    {
        op: "s3bucket", view: p => <S3BucketTabView {...p}/>, loadOpt: rareLoad,
        args: ({bucket_kube_context: kube_context, bucket_name})=> ({kube_context, bucket_name}),
    },
    {
        op: "allure", view: p => <AllureTabView {...p}/>, loadOpt: rareLoad,
    },
    {
        tab: "profiling", view: p => <ProfilingTabView {...p}/>,
    },
    {
        tab: "profiling", op: "profiling.profiling", view: p => <ProfilingPanel {...p}/>, loadOpt: rtLoad,
    },
    {
        tab: "profiling", op: "profiling.thread_dump", view: p => <ThreadDumpPanel {...p}/>, loadOpt: rtLoad,
    },
    {
        tab: "profiling", op: "profiling.logback", view: p => <LogbackPanel {...p}/>, loadOpt: rareLoad,
        args: ({profiling_kube_context: kube_context, profiling_pod_name: pod_name})=> ({kube_context, pod_name})
    },
    {
        op: "links", view: p => <LinksTabView {...p}/>, loadOpt: rareLoad,
    },
]

const ExchangingPanelAdapter = ({op, args, loadOpt, view, viewProps}) => {
    const {error, data} = useRQuery(loadOpt ? loadOpt({op, ...(args?args(viewProps):{})}) : {enabled: false})
    return error ? "*** Query Error ***" : op && !data ? "*** Loading ***" : view({...viewProps, ...data})
}

const ClusterPanel = ({clusters, showAllClusters, last_cluster, willNavigate}) => (
    <div className="flex justify-start items-center flex-wrap gap-2">
        {(clusters??[]).map((c) => (
            (showAllClusters || c.watch) &&
            <a key={c.name} href={toPath({
                op: "ind-login", name: c.name, location_hash: withHashParams({last_cluster:c.name})
            })} className={roundedFull(last_cluster === c.name)}>{c.name}</a>
        ))}
        <button onClick={willNavigate({showAllClusters: showAllClusters ? "":"1"})} className="text-sm text-blue-400 hover:underline">
            {showAllClusters ? 'Show less clusters for auth' : '... Show all clusters for auth'}
        </button>
    </div>
)

export const Page = () => {
    const [nav, willNavigate] = useNavigation("pods")
    const {errors: mutationErrors, addErrorText, delError} = useErrorList()
    const willSend = useAppMutation((err, r) => addErrorText(opMessages[opOf(r)] || `${opOf(r)} failed`))
    const [isFetching, pendingMutations] = usePending()
    const shared = useRQuery(staticLoad({op: "shared"}))
    const {mail, app_version} = shared.data ?? {}
    const isBusy = isFetching || pendingMutations?.length
    const viewProps = {...nav, willSend, willNavigate, pendingMutations}
    const activeCh = views.filter(v => v.tab && v.tab === nav?.tab || v.op && v.op === nav?.tab)
        .map(v => <ExchangingPanelAdapter {...v} viewProps={viewProps}/>)
    return (
        <div className="min-h-screen bg-gray-900 text-white p-4 font-sans flex flex-col items-center">
          <div className="w-full max-w-7xl">
            { app_version && app_version !== c4_app_version ? ReloadDialog("A new version is available.") : null }
            { shared.error && ReloadDialog("Connection problems.") }
            { mutationErrors.length > 0 && (
                <div className="fixed inset-x-0 top-0 z-50 flex flex-col items-center gap-2 pt-6 pointer-events-none">
                    {mutationErrors.map(e => (
                        <div key={e.text} className="pointer-events-auto mx-4 max-w-xl w-full bg-red-700 text-white shadow-2xl rounded-2xl px-6 py-4 flex items-start gap-4 border border-white/30">
                            <div className="text-2xl leading-none" aria-hidden>⚠️</div>
                            <div className="flex-1">
                                <div className="font-semibold">{e.text}</div>
                                <div className="text-xs text-white/90 mt-1 font-mono select-all break-all">{e.op}</div>
                            </div>
                            <button onClick={()=>delError(e)} className="bg-black/40 hover:bg-black/60 text-sm font-semibold py-2 px-4 rounded-lg">Dismiss</button>
                        </div>
                    ))}
                </div>
            )}

            <div className="mb-4 flex justify-between items-start">
                <ExchangingPanelAdapter op="clusters" loadOpt={staticLoad} viewProps={viewProps} view={p => <ClusterPanel {...p}/>}/>

                <div className="flex justify-end items-center gap-4">
                  <h1 className="text-xl font-semibold">{mail}</h1>
                  <a className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white" href="/oauth2/sign_out">Logout</a>
                  <div style={{animationDelay: "300ms"}} className={`${isBusy ? "animate-spin" : ""} rounded-full h-6 w-6 border-t-2 border-b-2 border-white`}></div>
                </div>
            </div>

            <div className="border-b border-gray-700 mb-4">
              <nav className="flex space-x-4 text-gray-300">
                {tabTitles.map(({keys,hint}) => (
                    <button key={keys[0]}
                      onClick={willNavigate({tab: keys[0]})}
                      className={`px-3 py-2 rounded-t-md ${keys.includes(nav.tab??'') ? 'bg-gray-800 text-white' : 'hover:bg-gray-700'}`}
                    >{hint}</button>
                ))}
              </nav>
            </div>

            {...activeCh}
          </div>
        </div>
    )
}

const compareBy = (dir, getKey) => (a, b) => dir * getKey(a).localeCompare(getKey(b))

const PodsTabView = viewProps => {
    const {user_abbr, items, pod_name_like, pod_contexts, sort_by_node, willSend, willNavigate} = viewProps
    const sortedItems = useMemo(() => sort_by_node ? items?.toSorted(compareBy(1, it => it.node_name||"")) : items, [items, sort_by_node])
    const minStartedAtByAppName = useMemo(() => items && Object.fromEntries(
        Object.entries(Object.groupBy(items.filter(pod=>pod.ready), pod=>pod.app_name))
            .flatMap(([app_name, its]) => its.length > 1 ? [[app_name, its.map(it=>it.started_at).toSorted()[0]]] : [])
    ), [items])
    return <>
          {pod_contexts && <div className="mb-4">
              <SelectorFilterGroup viewProps={viewProps} fieldName="filter_kube_context" items={pod_contexts.map(key => ({key,hint:key}))}/>
          </div>}

          <div className="mb-4 flex flex-wrap gap-2 justify-start">
              <SelectorFilterGroup viewProps={viewProps} fieldName="pod_name_like" items={[
                { key: `^(de|sp)-u?${user_abbr}.*-main-`, hint: `${user_abbr} pods` },
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
                        <TruncatedText text={pod.node_name||"-"} startChars={7} align="left"/>
                    </Td>
                    <Td>
                        <input type="radio" checked={pod.selected /*'✔️'*/}
                            onChange={willSend({ op: 'pods.select_pod', kube_context: pod.kube_context, name: pod.name })}
                        />
                    </Td>
                    <Td>
                      <div className="flex items-center gap-1">
                        <button
                            onClick={willNavigate({
                                tab: 'profiling', profiling_pod_name: pod.name, profiling_kube_context: pod.kube_context
                            })}
                            className="p-1"
                            title="Profile this pod"
                        >
                            📊
                        </button>
                        {pod.inbox_bucket && (
                            <button
                                onClick={willNavigate({
                                    tab: 's3bucket',
                                    bucket_kube_context: pod.kube_context,
                                    bucket_name: pod.inbox_bucket
                                })}
                                className="p-1"
                                title={`Open ${pod.inbox_bucket}`}
                            >
                                🪣
                            </button>
                        )}
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
                    <Td>
                        {pod.status}
                        {pod.ready && (
                            <div>
                                ready{pod.app_name && minStartedAtByAppName[pod.app_name] === pod.started_at ? " m" : ""}
                            </div>
                        )}
                    </Td>
                    <Td>{pod.creation_timestamp} <br/> {pod.started_at}</Td>
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
              return kube_context && user_abbr && <pre>{`
                  # operate selected:
                  kc ${kube_context} logs svc/fu-${user_abbr} -f --timestamps | grep ...
                  kc ${kube_context} exec -it svc/fu-${user_abbr} -- bash
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
    const {items, managed_kube_contexts = [], willSend, cio_kube_context} = viewProps
    return <>
          <div className="mb-4">
              <SelectorFilterGroup viewProps={viewProps} fieldName="cio_kube_context" items={managed_kube_contexts.map(key => ({key,hint:key}))}/>
          </div>
          <Table>
            <thead>
              <tr>
                <Th>Status</Th><Th>Queue</Th><Th>Task</Th><Th>PID</Th><Th>Actions</Th>
              </tr>
            </thead>
            <tbody>
                <NotFoundTr viewProps={viewProps} colSpan="5"/>
                { items?.map((t, index) => <Tr key={`${t.queue_name}/${t.task_name}/${t.pid ?? "none"}`} index={index}>
                    <Td>{t.status}</Td>
                    <Td>{t.queue_name}</Td>
                    <Td>{t.task_name}</Td>
                    <Td>{t.pid ?? "—"}</Td>
                    <Td>
                        <button
                            disabled={!t.pid || !cio_kube_context}
                            className={`px-3 py-1 rounded text-sm font-semibold ${
                                t.pid && cio_kube_context
                                    ? "bg-red-600 hover:bg-red-500 text-white"
                                    : "bg-gray-700 text-gray-400 cursor-not-allowed"
                            }`}
                            onClick={willSend({ op: 'cio_tasks.kill', kube_context: cio_kube_context, pid_str: `${t.pid}` })}
                        >Kill</button>
                    </Td>
                </Tr>)}
            </tbody>
          </Table>
    </>
}

const CIOEventsTabView = viewProps => {
    const {items, willSend, willNavigate, cio_events_task_like, cio_events_sort, cio_events_sort_dir} = viewProps
    const taskFilterRegex = useMemo(() => {
        const pattern = (cio_events_task_like || "").trim()
        if (!pattern) return { regex: null, error: null }
        try {
            return { regex: new RegExp(pattern, "i"), error: null }
        } catch (e) {
            return { regex: null, error: e?.message || "Invalid regex" }
        }
    }, [cio_events_task_like])
    const sortKey = cio_events_sort || "task"
    const sortDesc = cio_events_sort_dir === 'desc'
    const filteredItems = useMemo(
        () => (items || []).filter(t => (
            !taskFilterRegex.regex || taskFilterRegex.regex.test(t.task || "")
        )).map(t => (
            {...t, atStr: new Date(t.at*1000).toISOString() }
        )).toSorted(compareBy(sortDesc ? -1 : 1,it => it[sortKey])),
        [items, taskFilterRegex, sortKey, sortDesc]
    )
    const hideFiltered = async () => {
        if (taskFilterRegex.error || filteredItems.length === 0) return
        if (!confirm(`Hide ${filteredItems.length} filtered event(s)?`)) return
        for (const t of filteredItems) {
            willSend({ op: 'cio_events.hide', kube_context: t.kube_context, task: t.task })()
        }
    }
    const sortAction = field => (
       willNavigate({ cio_events_sort: field, cio_events_sort_dir: sortKey === field && !sortDesc ? 'desc' : '' })
    )
    const sortArrow = field => sortKey === field && (sortDesc ? '↑' : '↓')
    return <>
          <div className="mb-4 flex gap-2 items-center">
              <SimpleFilterInput
                  viewProps={viewProps}
                  fieldName="cio_events_task_like"
                  placeholder="Filter task content (regex)..."
              />
              <button
                  onClick={hideFiltered}
                  disabled={!!taskFilterRegex.error || filteredItems.length === 0}
                  className="bg-yellow-500 text-black px-3 py-1 rounded hover:bg-yellow-400 disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Hide all currently filtered events"
              >
                  Hide Filtered ({filteredItems.length})
              </button>
          </div>
          <div className="mb-4">
              {taskFilterRegex.error && (
                  <div className="text-xs text-red-300">Invalid regex: {taskFilterRegex.error}</div>
              )}
          </div>
          <Table>
            <thead>
              <tr>
                  <Th>Context</Th>
                  <Th onClick={sortAction("task")}>Task {sortArrow("task")}</Th>
                  <Th>Status</Th>
                  <Th onClick={sortAction("atStr")}>At {sortArrow("atStr")}</Th>
                  <Th>Actions</Th>
              </tr>
            </thead>
            <tbody>
                {filteredItems.length === 0 && <Tr>
                    <Td colSpan="5">{items?.length > 0 ? "Not found" : "Select more filters ..."}</Td>
                </Tr>}
                { filteredItems.map((t, index) => <Tr key={`${t.kube_context}/${t.task}`} index={index}>
                    <Td>{t.kube_context}</Td><Td>{t.task}</Td><Td>{t.status}</Td><Td>{t.atStr}</Td>
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
    const { all_log_sizes, cio_kube_context, cio_query, cio_context_lines, searches, willSend } = viewProps
    const downloadUrl = (id, head, tail) => toPath({ op: "cio_logs.download", id, head, tail })

    return (
        <div className="space-y-6 p-4 text-sm text-white">
            {/* Filter controls — a new search appends a row to the history below */}
            <div className="flex gap-2 flex-wrap items-center">
                <SelectorFilterGroup
                    viewProps={viewProps}
                    fieldName="cio_kube_context"
                    items={(all_log_sizes || []).map(c => ({
                        key: c.kube_context,
                        hint: `${c.kube_context} (${formatLogSize(c.log_size)})`
                    }))}
                />
                <SimpleFilterInput viewProps={viewProps} fieldName="cio_query" placeholder="Search query..." />
                <SimpleFilterInput viewProps={viewProps} fieldName="cio_context_lines" placeholder="Context lines..." />
                <button
                    onClick={willSend({
                        op: 'cio_logs.search', kube_context: cio_kube_context, query: cio_query,
                        context_lines: cio_context_lines || "0",
                    })}
                    className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                >
                    Search
                </button>
            </div>

            {/* History: one row per search, filters frozen as run. Big result? grab head/tail and scroll elsewhere. */}
            <table className="w-full text-left border border-gray-700">
                <thead className="text-gray-400">
                    <tr>
                        <th className="p-2">Time</th><th className="p-2">Query</th><th className="p-2">Context</th>
                        <th className="p-2">±lines</th><th className="p-2">Lines</th><th className="p-2">Result</th><th className="p-2"></th>
                    </tr>
                </thead>
                <tbody>
                    {(searches || []).map(s => (
                        <tr key={s.id} className="border-t border-gray-700 align-top">
                            <td className="p-2 text-gray-400 whitespace-nowrap">{new Date(Number(s.id) / 1e6).toLocaleTimeString()}</td>
                            <td className="p-2 font-mono break-all">{s.query}</td>
                            <td className="p-2">{s.kube_context}</td>
                            <td className="p-2">{s.context_lines}</td>
                            <td className="p-2">{s.result_lines ?? ""}</td>
                            <td className="p-2">{
                                s.result_code == null ? <span className="text-gray-400">Searching…</span> :
                                s.result_code > 1 ? <span className="text-red-300">Error</span> :
                                s.result_code === 1 ? <span className="text-gray-400">Not found</span> :
                                <span className="flex gap-3">
                                    <a className="underline hover:text-blue-400" href={downloadUrl(s.id, "", "")} {...tBlank()}>Download all</a>
                                    <a className="underline hover:text-blue-400" href={downloadUrl(s.id, 1000, "")} {...tBlank()}>head 1k</a>
                                    <a className="underline hover:text-blue-400" href={downloadUrl(s.id, "", 1000)} {...tBlank()}>tail 1k</a>
                                </span>
                            }</td>
                            <td className="p-2"><button onClick={willSend({ op: 'cio_logs.forget', id: s.id })} className="text-gray-500 hover:text-red-300">×</button></td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    )
}

const formatS3Size = v => `${(v / 1024 / 1024).toFixed(1)} MiB`;
const S3SnapshotsTabView = viewProps => {
    const { items, status_message, s3contexts, filter_kube_context, bucket_name_like, willSend, willNavigate } = viewProps
    const runSearch = willSend({ op: 's3.search', kube_context: filter_kube_context, bucket_name_like })
    return (
        <>
            <div className="flex gap-2 mb-4">
                <SelectorFilterGroup
                    viewProps={viewProps}
                    fieldName="filter_kube_context"
                    items={(s3contexts||[]).map(key => ({ key, hint: key }))}
                />
                <SimpleFilterInput
                    viewProps={viewProps}
                    fieldName="bucket_name_like"
                    placeholder="Filter buckets..."
                    onKeyDown={e => {
                        if (e.key === "Enter") {
                            e.preventDefault()
                            runSearch()
                        }
                    }}
                />
                <button
                    onClick={runSearch}
                    className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                    disabled={!filter_kube_context}
                >Search</button>
            </div>

            {status_message ? (
                <div className="bg-gray-800 border border-gray-600 rounded-lg p-6 text-center">
                    <p className="text-white text-lg mb-2">{status_message}</p>
                    <p className="text-gray-400">Run a new search to refresh this list.</p>
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
                                <button
                                    onClick={willNavigate({
                                        tab: 's3bucket',
                                        bucket_kube_context: filter_kube_context,
                                        bucket_name: b.bucket_name,
                                    })}
                                    className="bg-gray-700 hover:bg-gray-600 text-white px-2 py-1 rounded text-sm mr-2 disabled:opacity-40"
                                    disabled={!filter_kube_context}
                                    title="View objects"
                                >
                                    🔍
                                </button>
                                {b.has_reset_file && <span className="text-gray-400 text-sm">🔄 Reset pending</span>}
                            </Td>
                        </Tr>
                    ))}
                </tbody>
            </Table>}
        </>
    )
}

const S3BucketTabView = viewProps => {
    const { bucket_name, bucket_kube_context, bucket_objects, loaded_at, error, willSend } = viewProps
    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between">
                <div>
                    <h3 className="text-lg text-white">Objects in {bucket_name}</h3>
                    <div className="text-xs text-gray-400">
                        { !loaded_at ? "Loading…" : `Last updated ${new Date(loaded_at * 1000).toLocaleTimeString()}` }
                    </div>
                </div>
                <div className="flex gap-2">
                    {
                        bucket_name && bucket_name.match(/^(de|sp)-/) && bucket_objects?.every(o => o.key != ".reset") && <button
                            onClick={willSend({
                                op: 's3bucket.reset_bucket',
                                kube_context: bucket_kube_context,
                                bucket_name: bucket_name
                            })}
                            className="bg-red-600 hover:bg-red-500 text-white px-3 py-1 rounded text-sm"
                            title="Schedule snapshot reset"
                        >
                            Reset
                        </button>
                    }
                    <button
                        onClick={willSend({ op: 's3bucket.make_snapshot', kube_context: bucket_kube_context, bucket_name })}
                        className="bg-blue-600 hover:bg-blue-500 px-3 py-1 rounded text-white"
                        disabled={!bucket_name || !bucket_kube_context}
                    >
                        Make Snapshot
                    </button>
                    <button
                        onClick={willSend({ op: 'refresh' })}
                        className="bg-blue-600 hover:bg-blue-500 px-3 py-1 rounded text-white"
                        disabled={!bucket_name || !bucket_kube_context}
                    >
                        Refresh
                    </button>
                    <button
                        onClick={ev=>history.back()}
                        className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-white"
                    >
                        Back
                    </button>
                </div>
            </div>
            { !bucket_name || !bucket_kube_context ? (
                <div className="bg-gray-800 border border-gray-600 rounded-lg p-6 text-center text-gray-300">
                    No bucket selected.
                </div>
            ) : !loaded_at ? (
                <div className="bg-gray-800 border border-gray-700 rounded p-3 text-gray-300 text-sm">
                    Loading latest objects…
                </div>
            ) : error ? (
                <div className="bg-red-900 border border-red-600 rounded p-3 text-red-100 text-sm">{
                    error === 'too_many' ?
                        "Unable to display more than 1000 objects. Use CLI tools for detailed listing." :
                        "Failed. Try again later."
                }</div>
            ) : (
                <Table>
                    <thead>
                        <tr>
                            <Th>Key</Th>
                            <Th className="text-right">Size</Th>
                            <Th>Last Modified</Th>
                        </tr>
                    </thead>
                    <tbody>
                        {(!bucket_objects || bucket_objects.length === 0) && (
                            <tr>
                                <Td colSpan="3" className="text-center text-gray-400 py-6">
                                    No objects found
                                </Td>
                            </tr>
                        )}
                        {bucket_objects?.map((obj, index) => (
                            <Tr key={obj.key} index={index}>
                                <Td><TruncatedText text={obj.key} startChars={24} align="left"/></Td>
                                <Td className="text-right">{formatS3Size(obj.size || 0)}</Td>
                                <Td>{obj.last_modified ? String(obj.last_modified).split(".")[0] : "-"}</Td>
                            </Tr>
                        ))}
                    </tbody>
                </Table>
            )}
        </div>
    )
}

const AllureTabView = viewProps => {
    const {items, loaded_at, error, allure_query, willSend} = viewProps
    const query = (allure_query || "").trim().toLowerCase()
    const filteredItems = useMemo(
        () => (items || []).filter(r => (
            !query || r.project.toLowerCase().includes(query) || r.run.toLowerCase().includes(query)
        )),
        [items, query]
    )
    return (
        <div className="space-y-4">
            <div className="flex flex-wrap gap-2 items-center justify-between">
                <div className="flex flex-wrap gap-2 items-center">
                    <SimpleFilterInput viewProps={viewProps} fieldName="allure_query" placeholder="Filter project / run..."/>
                    <button
                        onClick={willSend({ op: 'refresh' })}
                        className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
                    >
                        Refresh
                    </button>
                </div>
                <div className="text-xs text-gray-400">
                    {loaded_at ? `Last updated ${new Date(loaded_at * 1000).toLocaleTimeString()}` : "Loading..."}
                </div>
            </div>
            {!items ? (
                <div className="bg-gray-800 border border-gray-700 rounded p-3 text-gray-300 text-sm">
                    Loading Allure reports...
                </div>
            ) : (
                <Table>
                    <thead>
                        <tr>
                            <Th>Time</Th>
                            <Th>Project</Th>
                            <Th>Run</Th>
                            <Th>HTML</Th>
                            <Th>TGZ</Th>
                        </tr>
                    </thead>
                    <tbody>
                        {filteredItems.length === 0 && (
                            <Tr>
                                <Td colSpan="5">{items.length > 0 ? "Not found" : "No reports found"}</Td>
                            </Tr>
                        )}
                        {filteredItems.map((r, index) => (
                            <Tr key={r.run} index={index}>
                                <Td>{r.ts}</Td>
                                <Td>{r.project}</Td>
                                <Td className="font-mono text-xs">{r.run}</Td>
                                <Td>
                                    {r.html && <a className="underline hover:text-blue-400" href={`/allure/${r.html}index.html`} {...tBlank()}>open</a>}
                                </Td>
                                <Td>
                                    {r.tgz && <a className="underline hover:text-blue-400" href={`/allure/${r.tgz}`} {...tBlank()}>tgz</a>}
                                </Td>
                            </Tr>
                        ))}
                    </tbody>
                </Table>
            )}
        </div>
    )
}

const ProfilingTabView = viewProps => {
    const { profiling_kube_context, profiling_pod_name, willSend } = viewProps
    return (
            <div className="mb-6 bg-gray-800 border border-gray-700 rounded p-4">
                <h3 className="text-sm uppercase tracking-wide text-gray-400">Target pod</h3>
                <p className="text-lg font-semibold text-white">
                    {profiling_pod_name || "No pod selected"}
                </p>
                <p className="text-xs text-gray-500 mt-1">
                    Context: {profiling_kube_context || "-"}
                </p>
                {profiling_kube_context && profiling_pod_name && (
                    <p className="mt-1">
                        <button
                            onClick={willSend({
                                op: 'profiling.enable_gc_log',
                                kube_context: profiling_kube_context,
                                pod_name: profiling_pod_name
                            })}
                            className="bg-gray-600 hover:bg-gray-500 px-4 py-1 rounded text-white w-fit"
                        >
                            Enable gc log
                        </button>
                    </p>
                )}
            </div>
    )
}

const ProfilingPanel = viewProps => {
    const {
        profiling_kube_context, profiling_pod_name, profiling_period, profiling_status, profiling_spent, willSend
    } = viewProps
    return <div className="mb-6 bg-gray-800 border border-gray-700 rounded p-4 space-y-3">
        <div className="flex items-center justify-between">
            <h3 className="text-sm uppercase tracking-wide text-gray-400">Flame graph</h3>
        </div>
        <div className="flex flex-wrap gap-2 items-center">
        {
            !profiling_status ? <SelectorFilterGroup
                viewProps={viewProps}
                fieldName="profiling_period"
                items={[{ key: "15", hint: "15s" }, { key: "", hint: "60s" }, { key: "300", hint: "300s" }]}
            /> :
            profiling_status === "P" ? <p className="text-xs text-gray-500">Profiling… {Math.round(profiling_spent)}s</p> :
            profiling_status === "S" ? <a
                className="underline hover:text-blue-400"
                href={`/profiling.flamegraph.html?time=${Date.now()}`}
                {...tBlank()}
            >Download flame graph</a> :
            profiling_status === "F" ? <p className="text-red-300">Profiling failed.</p> : null
        }{
            profiling_status ? <button
                onClick={willSend({ op: 'profiling.reset_profile_status' })}
                className="bg-blue-600 hover:bg-blue-500 px-3 py-1 rounded text-white"
            >Clear</button> :
            profiling_kube_context && profiling_pod_name ? <button
                onClick={willSend({
                    op: 'profiling.profile',
                    kube_context: profiling_kube_context,
                    pod_name: profiling_pod_name,
                    period: profiling_period || "60"
                })}
                className="bg-blue-600 hover:bg-blue-500 px-4 py-1 rounded text-white"
            >Profile</button> : null
        }
        </div>
    </div>
}

const ThreadDumpPanel = viewProps => {
    const { profiling_kube_context, profiling_pod_name, thread_dump_status, pendingMutations, willSend } = viewProps
    const threadDumpAct = {
        op: 'profiling.thread_dump', kube_context: profiling_kube_context, pod_name: profiling_pod_name
    }
    const threadDumpBusy = pendingMutations.includes(toPath(threadDumpAct))
    const hasSelection = profiling_kube_context && profiling_pod_name

    return (
                    hasSelection && <div className="mb-6 bg-gray-800 border border-gray-700 rounded p-4 space-y-3">
                        <div className="flex items-center justify-between">
                            <h3 className="text-sm uppercase tracking-wide text-gray-400">Thread dump</h3>
                            {threadDumpBusy ? (
                                <p className="text-xs text-gray-500">Collecting…</p>
                            ) : null}
                        </div>
                        {thread_dump_status === "S" ? (
                            <div className="flex gap-2 items-center">
                                <a
                                    className="underline hover:text-blue-400"
                                    href={`/profiling.thread_dump.html?time=${Date.now()}`}
                                    {...tBlank()}
                                >
                                    Download thread dump
                                </a>
                                <button
                                    onClick={willSend({ op: 'profiling.reset_thread_status' })}
                                    className="bg-blue-600 hover:bg-blue-500 px-3 py-1 rounded text-white"
                                >
                                    Clear
                                </button>
                            </div>
                        ) : !threadDumpBusy ? (
                            <p className="text-xs text-gray-500">No thread dump collected yet.</p>
                        ) : null}
                        {!threadDumpBusy ? (
                            <button
                                onClick={willSend(threadDumpAct)}
                                className="bg-gray-600 hover:bg-gray-500 px-4 py-1 rounded text-white w-fit"
                            >
                                Collect thread dump
                            </button>
                        ) : null}
                    </div>
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

const LogbackPanel = viewProps => {
    const {
        profiling_kube_context: kubeContext, profiling_pod_name: podName, logback_loaded: logbackLoaded, willSend
    } = viewProps
    const hasSelection = kubeContext && podName
    const [logbackCustomClass, setLogbackCustomClass] = useState("")
    const currentClasses =
        logbackLoaded ? normalizeLogbackClasses(parseLogbackClasses(logbackLoaded)) : logbackLoaded === "" ? [] : null
    const logbackPresets = [
        {
            name: "proto preset",
            classes: [
                "ee.cone.c4actor.TxAddImpl",
                "ee.cone.c4actor.QMessagesImpl",
                "ee.cone.c4actor.TxTrLogger",
                "ee.cone.c4actor.AssemblerUtil",
                "ee.cone.c4gate_server.SnapshotMakerImpl",
                "ee.cone.c4actor.AssemblerProfiling",
            ]
        },
        {
            name: "assemble",
            classes: [
                "ee.cone.c4actor.RAssProfilingImpl",
            ]
        },
    ]
    const applyClasses = classes => {
        const xml = buildLogbackXml(normalizeLogbackClasses(classes))
        willSend({ op: 'profiling.save_logback', kube_context: kubeContext, pod_name: podName, logback_xml: xml })()
    }
    return (
        hasSelection && <div className="mb-6 bg-gray-800 border border-gray-700 rounded p-4 space-y-4">
            <div className="flex items-center justify-between">
                <h3 className="text-sm uppercase tracking-wide text-gray-400">LOG CLASSES</h3>
                <div className="flex items-center gap-2">
                    {currentClasses !== null && (
                        <button
                            onClick={willSend({ op: 'profiling.unload_logback', kube_context: kubeContext, pod_name: podName })}
                            className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-xs text-white"
                        >
                            Close
                        </button>
                    )}
                </div>
            </div>
            { !currentClasses ? (
                <div className="flex items-center gap-2">
                    <button
                        onClick={willSend({ op: 'profiling.load_logback', kube_context: kubeContext, pod_name: podName })}
                        className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-xs"
                    >
                        Start editing
                    </button>
                    <span className="text-xs text-gray-500">Loads /tmp/logback.xml on demand.</span>
                </div>
            ) : (
                <>
                    <div className="space-y-2">
                        {currentClasses.length === 0 ? <p className="text-xs text-gray-500">No logger entries.</p> : (
                            <div className="flex flex-wrap gap-2">
                                {currentClasses.map(cls => (
                                    <span key={cls} className="flex items-center gap-2 bg-gray-900 border border-gray-700 rounded-full px-3 py-1 text-xs">
                                        <span className="text-gray-200">{cls}</span>
                                        <button
                                            onClick={() => applyClasses(currentClasses.filter(item => item !== cls))}
                                            className="text-gray-400 hover:text-white"
                                            title={`Remove ${cls}`}
                                        >
                                            ×
                                        </button>
                                    </span>
                                ))}
                            </div>
                        )}
                    </div>
                    <div className="flex flex-wrap gap-2 items-center">
                        {logbackPresets.map(preset => (
                            <button
                                key={preset.name}
                                onClick={() => applyClasses([...currentClasses, ...preset.classes])}
                                className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-xs"
                            >
                                {preset.name}
                            </button>
                        ))}
                        <button
                            onClick={() => applyClasses([])}
                            className="bg-gray-700 hover:bg-gray-600 px-3 py-1 rounded text-xs"
                        >
                            Reset all
                        </button>
                    </div>
                    <div className="flex flex-wrap gap-2 items-center">
                        <input
                            value={logbackCustomClass}
                            onChange={ev => setLogbackCustomClass(ev.target.value)}
                            placeholder="ee.cone.MyClass"
                            className="bg-gray-900 border border-gray-700 rounded px-3 py-1 text-xs text-gray-200 w-64"
                        />
                        <button
                            onClick={() => {
                                applyClasses([...currentClasses, logbackCustomClass])
                                setLogbackCustomClass("")
                            }}
                            className="bg-blue-600 hover:bg-blue-500 px-3 py-1 rounded text-xs text-white"
                        >
                            Add class
                        </button>
                    </div>
                </>
            )}
        </div>
    )
}

const normalizeLogbackClasses = classes => (
    [...new Set((classes || []).map(item => (item || "").trim()).filter(Boolean))].toSorted()
)

const parseLogbackClasses = xml => {
    if (!xml) return []
    const parser = new DOMParser()
    const doc = parser.parseFromString(`<configuration>${xml}</configuration>`, "application/xml")
    if (doc.getElementsByTagName("parsererror").length) return []
    return [...doc.getElementsByTagName("logger")]
        .map(node => node.getAttribute("name"))
        .filter(Boolean)
}

const buildLogbackXml = classes => {
    const serializer = new XMLSerializer()
    const parser = new DOMParser()
    const doc = parser.parseFromString("<configuration></configuration>", "application/xml")
    const root = doc.documentElement
    normalizeLogbackClasses(classes).forEach(name => {
        const logger = doc.createElement("logger")
        logger.setAttribute("name", name)
        logger.setAttribute("level", "DEBUG")
        root.appendChild(logger)
    })
    return [...root.children].map(node => serializer.serializeToString(node)).join("\n")
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

start(<Page/>)
