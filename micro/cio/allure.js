
onload = () => {
    const pRuns = RUNS.map(name => {
        const m = name.match(/^run\.([^.]+)\.([^.]+)\.(unp\/|tgz)$/)
        return m && {
            ts: m[1],
            project: m[2],
            kind: m[3] === "unp/" ? "html" : "tgz",
            run: `run.${m[1]}.${m[2]}`,
            href: name,
        }
    }).filter(Boolean)
    const grouped = Object.values(Object.groupBy(pRuns, r => r.run)).map(group => ({
        run: group[0].run,
        ts: group[0].ts,
        project: group[0].project,
        html: group.find(x => x.kind === "html")?.href,
        tgz: group.find(x => x.kind === "tgz")?.href,
    }))
    const rowsHtml = grouped
        .sort((a, b) => b.run.localeCompare(a.run))
        .map(r => `
        <tr>
          <td>${r.ts}</td>
          <td>${r.project}</td>
          <td class="run">${r.run}</td>
          <td>${r.html ? `<a href="${r.html}index.html">open</a>` : ""}</td>
          <td>${r.tgz ? `<a href="${r.tgz}">tgz</a>` : ""}</td>
        </tr>
      `).join("")

    document.body.innerHTML = `
      <meta charset="utf-8">
      <style>
        body { font-family: system-ui, sans-serif; margin: 2rem; }
        input { margin: 0 0 1rem 0; padding: .4rem; width: 32rem; max-width: 100%; }
        table { border-collapse: collapse; min-width: 60rem; }
        th, td { border-bottom: 1px solid #ddd; padding: .45rem .7rem; text-align: left; }
      </style>
      <h1>Allure reports</h1>
      <input id="q" placeholder="filter project / run">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Project</th>
            <th>Run</th>
            <th>HTML</th>
            <th>TGZ</th>
          </tr>
        </thead>
        <tbody id="rows">${rowsHtml}</tbody>
      </table>
    `

    const runs = [...document.querySelectorAll(".run")]
        .map(el => ({ el, text: el.textContent.toLowerCase() }));

    const render = () => {
        const v = q.value.toLowerCase()
        for (const r of runs) {
            r.el.parentElement.style.display =
                (!v || r.text.includes(v)) ? "" : "none"
        }
    }

    q.oninput = render
    render()
}
