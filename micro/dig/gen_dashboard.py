from json import dumps

def pos(x,y): return { "gridPos":{"x":x*8,"y":y*6,"w":8,"h":6} }

print(dumps({
    "title": "C4 System Status",
    "panels": [
        { "type": "stat", "title": "Synthetic OK", **pos(0,0), "targets": [
            { "expr": 'c4synthetic_ok{exported_app=~"$app-.*"}', "legendFormat": "{{exported_app}}" },
        ]},
        { "type": "timeseries", "title": "Synthetic Latency", **pos(0,1), "targets": [
            { "expr": 'c4synthetic_latency_seconds{exported_app=~"$app-.*"}', "legendFormat": "{{exported_app}}" },
        ]},
        { "type": "timeseries", "title": "Node CPU", **pos(1,0), "targets": [
            { "expr": 'rate(c4dig:cpu_idle{pod=~"$app-.*"}[2m]) / 100', "legendFormat": "{{pod}} idle cores" },
        ]},
        { "type": "timeseries", "title": "Node Memory", **pos(2,0), "targets": [
            { "expr": 'c4dig:mem_available{pod=~"$app-.*"} / 1024 / 1024', "legendFormat": "available {{pod}} GiB" },
            { "expr": 'c4dig:mem_total{pod=~"$app-.*"} / 1024 / 1024', "legendFormat": "total {{pod}} GiB" },
        ]},
        { "type": "timeseries", "title": "Process Memory", **pos(2,1), "targets": [
            { "expr": 'c4dig:vm_rss_kb{pod=~"$app-.*"} / 1024 / 1024', "legendFormat": "RSS {{pod}} GiB" },
            { "expr": 'c4dig:heap_max{pod=~"$app-.*"} / 1024 / 1024 / 1024', "legendFormat": "head max {{pod}} GiB" },
            { "expr": 'c4dig:heap_used_kb{pod=~"$app-.*"} / 1024 / 1024', "legendFormat": "heap used {{pod}} GiB" },
        ]},
        { "type": "timeseries", "title": "Thread Count", **pos(1,1), "targets": [
            { "expr": 'c4dig:threads{pod=~"$app-.*"}', "legendFormat": "{{pod}}" },
        ]},
        { "type": "timeseries", "title": "Kafka Rates", **pos(0,2), "targets": [
            { "expr": 'rate(kafka_topic_partition_current_offset{topic=~"$app\\\\..*"}[10m])', "legendFormat": "{{topic}} rec/s" },
        ]},
    ],
    "templating": { "list": [
        { "name": "app", "query": "label_values(c4synthetic_ok, exported_app)", "type": "query", "regex": "/^(.*?)-main$/" },
    ]},
}, sort_keys=True, indent=4))



#"schemaVersion": 30,
#"version": 1
