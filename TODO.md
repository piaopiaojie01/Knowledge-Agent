# Knowledge Agent 企业化改造 TODO

> 状态图例：`[ ]` 待办 ｜ `[x]` 已完成
> 最近更新：2026-08-09（P0 安全加固 + P1 第一批可部署性已提交）

## P0 稳定性与安全（已完成，2026-08-11）

- [x] **Flyway 数据库迁移**：替代 `ddl-auto: update`，现有库 baseline v1 接管，新库走 V1 建表
- [x] **备份覆盖**：MySQL 逻辑备份 + Redis RDB + Milvus/MinIO/etcd 数据卷 + 宿主机运行目录，含恢复脚本与定时任务文档
- [x] **AgentClient 重试/熔断**：网络抖动自动重试（退避），连续失败熔断 30s 快速失败，区分查询/入库幂等
- [x] **入库任务队列化**：Redis 分布式锁（SET NX + Lua 释放）防多副本重复入库，任务状态 Redis 镜像跨实例可见，Redis 不可用自动降级
- [x] **token 移出 localStorage**：HttpOnly Cookie（SameSite=Strict）+ /auth/me 会话恢复 + 登出撤销黑名单并清 Cookie

## 权限管理（已完成，2026-08-10）

- [x] 用户启用/禁用：禁用即时生效（JWT 过滤器校验用户状态，存量 token 立即失效）
- [x] 管理员重置密码：重置后强制登出该用户全部会话
- [x] 强制登出：Redis user→jti 索引，一键撤销用户全部 token
- [x] 权限矩阵：按用户 / 按知识库两个视图，全局管理员可授权任意用户/任意知识库
- [x] 知识库公开/私有切换、全量知识库管理（管理员豁免 KB 级 ADMIN 校验）
- [x] 未认证请求统一返回 401 JSON，前端自动刷新/跳登录
- [x] 审计日志：授权/回收/禁用/重置密码/强制登出均记录
- [x] 后端 88 测试 + 前端 17 测试全绿，端到端验证通过

## P0 安全加固（已完成，commit f0c0039）

- [x] Agent 内部 API Key 鉴权（`X-KA-API-Key`），8000 端口不再对外映射
- [x] `calculate` 由 eval 改为 AST 白名单求值
- [x] `url_fetch` 域名白名单 + 内网/保留地址拦截（防 SSRF），重定向逐跳校验
- [x] Agent CORS 收紧（默认不开放跨域）
- [x] JWT `jti` + Redis 黑名单撤销、`/api/auth/logout`、refresh 校验
- [x] MySQL/Redis/Milvus/MinIO 端口绑定 127.0.0.1，Redis 启用口令
- [x] 上传大小上限（后端 100MB + Agent base64 校验）
- [x] Agent 错误信息收敛（不透传内部异常）
- [x] nginx / 后端 CSP 收紧（移除 `unsafe-inline`/`unsafe-eval`）
- [x] 前端 401 自动刷新重试、登出调用后端撤销
- [x] 图表/图标硬编码路径配置化（`KA_CHART_OUTPUT_DIR` 等）

## P1 可部署性

- [x] 三端 Dockerfile + `.dockerignore`（backend / agent / frontend）
- [x] compose 自包含构建：`docker compose up -d --build` 一条命令起全栈
- [x] 运行时数据卷 `ka_charts` / `ka_icons` / `ka_agent_data`，重建不丢
- [x] WebConfig 图表/图标目录配置化（`KA_CHART_DIR` / `KA_ICON_DIR`）
- [x] 修复 vite 代理端口 9898 → 8080

### 待办（P1 剩余）

- [ ] **真机验证 `docker compose up -d --build`**：首次构建 agent 镜像较慢（torch/docling 约 5-10GB），需实际跑通全栈并验证登录/上传/RAG 链路
- [x] **Flyway 数据库迁移**：替换 `ddl-auto: update`；现有库用 `baseline-on-migrate` 接管，测试环境需协调 H2 + Flyway
- [x] **Agent 入库任务队列化**：Redis 分布式锁 + 状态镜像，解决重启丢任务、多副本重复入库
- [x] **AgentClient 重试/熔断/降级**：轻量重试 + 熔断，区分查询/入库幂等性
- [ ] **可观测性**：请求 ID 贯穿 backend→agent、结构化日志、Prometheus 指标、日志聚合
- [x] **前端 token 移出 localStorage**：改 HttpOnly Cookie（SameSite=Strict）+ /auth/me 会话恢复

## P2 运维加固

- [ ] CI 增加镜像构建 + 依赖漏洞扫描（trivy） + secret 扫描
- [ ] CI 发布产物：backend jar / agent 镜像 / frontend 镜像推送私有仓库
- [x] 备份覆盖 Milvus / Redis / 运行时卷（deploy/backup：backup.ps1 / restore.ps1）
- [x] 备份恢复演练流程与文档（deploy/backup/README.md）
- [ ] 日志聚合 + 告警（systemd / Docker 日志 → Loki / ELK）
- [ ] 容量规划与资源限制（compose 加 mem_limit / cpus，模型加载约 4GB+）
- [ ] 生产部署默认 profile 统一为 prod（deploy.sh 目前走根目录 compose 的 dev profile）
- [ ] 统一 nginx 部署路径：容器版（frontend/nginx.conf）与宿主机版（deploy/nginx.conf）二选一维护

## P3 企业特性

- [ ] 统一登录对接（公司已有后台管理系统，后续从其登录；协议待定：OIDC / OAuth2 / 自定义票据桥接）
- [ ] 多租户隔离（租户级数据分区 + 权限继承）
- [ ] 文档版本管理、回收站、生命周期（归档/过期）
- [ ] 数据源连接器（Confluence / SharePoint / 数据库 / S3）
- [ ] 敏感信息脱敏（PII 识别与过滤）
- [ ] 审计报表与导出
- [ ] K8s / Helm 部署清单

## 已知小问题（低优先级）

- [ ] README 快速启动仍引用旧模型名/端口的地方需随配置漂移修正
- [ ] `index.html`（仓库根，0 字节）疑似残留文件，确认后删除
- [ ] RAG 审计日志未记录当前用户（username/userId 为空）
- [ ] 全局异常处理把业务异常统一吞为“请求处理失败”，排查体验差（建议统一错误码）
