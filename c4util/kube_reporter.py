
def single(items, get_default): return items[0] if len(items) == 1 else get_default()


def item_to_str(it):
    name = it.get("metadata", {}).get("name")
    if not name:
        return None
    spec = it.get("spec", {})
    node = "-".join(spec.get("nodeName", "").split("-")[:2])
    requests = single(spec.get("containers", []), lambda: {}).get("resources", {}).get("requests", {})
    req_str = "/".join(v for v in (requests.get("cpu", ""), requests.get("memory", "")) if v)
    container_status = single(it.get("status", {}).get("containerStatuses", []), lambda: {})
    image_pf = container_status.get("image", "").split(":")[-1]
    ready = container_status.get("ready")
    restart_count = str(container_status.get("restartCount", ""))
    started_at = container_status.get("state", {}).get("running", {}).get("startedAt", "")
    return name, image_pf, node, restart_count, started_at, "Y" if ready else "", req_str


def get_cluster_report(items):
    head = ("NAME", "TAG", "NODE", "RESTARTS", "STARTED", "READY", "REQ")
    right = {"RESTARTS", "REQ"}
    rows = [head] + sorted(it for it in [item_to_str(it) for it in items] if it)
    widths = [max(len(str(row[cn])) for row in rows) for cn in range(len(head))]
    return "\n".join(" ".join(
        (row[cn].rjust if head[cn] in right else row[cn].ljust)(widths[cn], " ")
        for cn in range(len(head))
    ) for row in rows)
