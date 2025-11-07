# memresize

Memresize watches pods in a single namespace, inspects the `main` container's memory usage from the metrics.k8s.io API, and adjusts that container's memory *request* in-place. Only pod names matching a configurable regex are considered, keeping the agent focused on a subset of workloads.

## Requirements
- Kubernetes cluster with the `InPlacePodVerticalScaling` feature gate enabled (v1.32+).
- Metrics server exposing `/apis/metrics.k8s.io/v1beta1/*`.
- Service account RBAC permitting `get` on pods and metrics plus `patch` on pods (with the `resize` subresource).

## Configuration
Set the following environment variables to tune behaviour. Values shown in **bold** are defaults.

| Variable | Default | Purpose |
| --- | --- | --- |
| `C4KUBECONFIG` | *(required)* | Path to kubeconfig used for all calls. |
| `C4MEM_KUBE_CONTEXT` | *(required)* | Kube context to target. |
| `C4MEM_KUBE_NS` | *(required)* | Namespace containing the pods to manage. |
| `C4MEM_POD_LIKE` | **`^de-`** | Regex; pod name must match to be considered. |
| `C4MEM_SCALE_UP_TRIGGER` | **`1.2`** | Resize upward when usage/request ≥ threshold. |
| `C4MEM_SCALE_DOWN_TRIGGER` | **`0.5`** | Resize downward when usage/request ≤ threshold. |
| `C4MEM_SLEEP_FOR_SECONDS` | **`120`** | Delay between iterations. |
| `C4MEM_DRY_RUN` | *(absent)* | Set to `1` to log patches without applying them. |

The agent understands memory quantities reported in `Ki`, `Mi`, or `Gi`. Usage data comes from the metrics API, while requests are read from the live pod spec.

## Local run
```
C4KUBECONFIG=~/.kube/config \
C4MEM_KUBE_CONTEXT=clusterA \
C4MEM_KUBE_NS=test \
python memresize/main.py
```
Toggle dry-run behaviour by exporting `C4MEM_DRY_RUN=1` until you're comfortable with the suggested patches.
