# Knowledge Agent 部署与运维文档

## 一、一键部署（推荐）

前提：已安装 Docker Desktop 并启动；有 NVIDIA GPU 的机器首次构建 agent 镜像较慢（含 torch，约 5~15 分钟）。

```powershell
# 在仓库根目录执行
powershell -ExecutionPolicy Bypass -File deploy\install.ps1
```

脚本会依次：

1. 检查 Docker 是否运行；
2. **交互式让你配置**：
   - 系统管理员用户名 / 密码（8 位以上，含大小写、数字、特殊字符；这就是你登录系统的账号）
   - MySQL root 密码、应用库密码（DB_PASS）、Redis 密码、MinIO 密钥、JWT 签名密钥、Agent 内部密钥 —— **直接回车自动生成随机强密码**
   - HTTPS 是否启用（决定 Cookie 是否加 Secure）
3. 检测 8080/80 端口是否被占用（被占用会提示你换端口）；
4. 生成 `.env`（已 gitignore，不会提交）并 `docker compose up -d --build` 构建启动全栈；
5. 等待后端健康检查通过，打印访问地址和管理员账号。

> 首次启动时后端会根据 `.env` 里的 `KA_ADMIN_USERNAME/KA_ADMIN_PASSWORD` **自动创建管理员账号**；如果库里还是默认种子密码（admin123），会自动升级为你配置的密码。

已生成 `.env` 后，只启动/停止服务：

```powershell
docker compose -f deploy\docker-compose.yml --env-file .env up -d          # 启动
docker compose -f deploy\docker-compose.yml --env-file .env down           # 停止
docker compose -f deploy\docker-compose.yml --env-file .env up -d --build  # 重新构建并启动（升级后）
```

## 二、手动部署

```powershell
Copy-Item .env.example .env          # 1. 复制配置模板
notepad .env                         # 2. 填好所有密码/密钥
docker compose -f deploy\docker-compose.yml --env-file .env up -d --build  # 3. 构建启动
```

## 三、配置项说明

| 变量 | 用途 | 说明 |
|---|---|---|
| `KA_ADMIN_USERNAME` / `KA_ADMIN_PASSWORD` | 系统管理员账号 | 首次启动自动创建/升级默认密码 |
| `MYSQL_ROOT_PASSWORD` | MySQL root 口令 | 仅初始化时生效，别改旧库 |
| `DB_USER` / `DB_PASS` | 应用数据库账号口令 | 与后端连接一致 |
| `REDIS_PASS` | Redis 口令 | 后端与 Agent 共用 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 对象存储密钥 | Milvus 底层存储，compose 已透传 |
| `JWT_SECRET` | 登录 Token 签名密钥 | 至少 32 字节随机串 |
| `KA_INTERNAL_API_KEY` | 后端调用 Agent 内部密钥 | 后端与 Agent 必须一致 |
| `KA_DEEPSEEK_API_KEY` | 大模型 API Key | 可留空，之后在管理后台模型配置里填 |
| `KA_COOKIE_SECURE` | Cookie Secure 标志 | HTTPS 部署置 `true` |
| `BACKEND_PORT` / `HTTP_PORT` / `HTTPS_PORT` | 宿主机端口映射 | 默认 8080 / 80 / 443，冲突可改 |

## 四、首次登录与改密码

- 前端：`http://localhost:80`（或你配置的 HTTP_PORT）
- 账号：一键部署时设置的 `KA_ADMIN_USERNAME`
- 登录后进入「管理后台 → 用户管理」可重置任意用户（含自己）的密码
- 忘记管理员密码：用 `docker exec -it ka-mysql mysql -uroot -p` 进库，或把 `.env` 的 `KA_ADMIN_PASSWORD` 改为新密码后重启后端（仅当原密码仍是默认种子时才自动覆盖，如已被改过需手工 SQL 更新）

## 五、日常运维

```powershell
# 备份（MySQL + Redis + Milvus/MinIO/etcd 卷 + 运行目录）
powershell -ExecutionPolicy Bypass -File deploy\backup\backup.ps1 -MySqlPwd <root密码>

# 恢复
powershell -ExecutionPolicy Bypass -File deploy\backup\restore.ps1 -BackupPath <备份目录> -MySqlPwd <root密码>

# 查看日志
docker logs -f ka-backend
docker logs -f ka-agent
docker logs -f ka-nginx
```

Linux 服务器部署可用 `deploy/backup-db.sh`（读 `/etc/ka.env`，模板见 `deploy/ka.env.example`）：

```bash
sudo cp deploy/ka.env.example /etc/ka.env
sudo chmod 600 /etc/ka.env
sudo crontab -e   # 0 3 * * * /opt/knowledge-agent/deploy/backup-db.sh
```

## 六、常见问题

| 现象 | 处理 |
|---|---|
| 启动报「缺少 XXXX」 | 没配 `.env`，复制 `.env.example` 填好再启动 |
| 8080/80 端口被占用 | 在 `.env` 里改 `BACKEND_PORT` / `HTTP_PORT` |
| 首次构建很慢 | agent 镜像含 torch/docling，属正常，耐心等 |
| GPU 显存不足 | 修改 `agent/.env` 的 `KA_EMBEDDING_DEVICE=cpu` |
| 前端登录后 401 | Cookie 跨域问题：请用 `HTTP_PORT` 对应域名访问，或检查 `KA_CORS_ORIGINS` |
| 问答报「Agent 不可达」 | `docker logs ka-agent` 看是否加载模型失败；确认 `KA_INTERNAL_API_KEY` 两端一致 |

## 七、安全说明

- `.env` 含全部密码，务必 gitignore（已配置）且不要外发；
- 生产环境请使用强随机密码（脚本回车自动生成的即可），并置 `KA_COOKIE_SECURE=true` + HTTPS；
- 定期执行备份（见第五节），备份目录不要放在 Web 可达路径下。
