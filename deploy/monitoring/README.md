# Knowledge Agent 监控大盘

Prometheus + Grafana 组成的可观测性大盘，采集后端（Spring Boot）与 Agent（FastAPI）的指标。

## 启动

```powershell
docker compose -f deploy/monitoring/docker-compose.yml up -d
```

## 访问

| 服务 | 地址 | 账号 |
|---|---|---|
| Grafana 大盘 | http://localhost:3000 | admin / ka_grafana_2026 |
| Prometheus | http://localhost:9090 | - |

大盘：Grafana → Knowledge Agent 文件夹 → **Knowledge Agent 可观测性大盘**（自动通过 provisioning 加载，无需手动导入）。

## 指标来源

- 后端：http://localhost:8082/actuator/prometheus（`ka_rag_queries_total`、`ka_rag_tokens_total`、`ka_rag_streams_total`、`ka_docs_uploaded_total`、JVM/HTTP 指标）
- Agent：http://localhost:8000/metrics（`ka_rag_requests_total`、`ka_rag_query_seconds`、`ka_llm_tokens_total`、`ka_ingest_chunks_total`、`ka_agent_health_checks_total`）

> 注意：dev 模式下后端/Agent 跑在宿主机（非容器），Prometheus 容器通过 `host.docker.internal` 采集，见 `prometheus.yml`。若宿主机端口变化，改这里即可。

## 仪表盘面板

- 业务指标：RAG 查询总量（成功/失败）、Token 总量、LLM Token 明细、流式查询、文档上传、入库分块
- 趋势与性能：查询速率 QPS、Token 消耗速率、Agent 查询耗时分位 p50/p90/p99、后端 HTTP 请求速率
- 系统健康：JVM 堆使用率、后端存活时间、Agent 健康检查、查询平均耗时

`ka_rag_streams_total` / `ka_docs_uploaded_total` 在首次使用流式问答/上传文档后才会出现数据，属正常现象。

## 停止

```powershell
docker compose -f deploy/monitoring/docker-compose.yml down
```

数据保留在 volume（`prometheus_data` / `grafana_data`）中，`down` 不会清除。
