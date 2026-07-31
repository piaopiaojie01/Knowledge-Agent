# Knowledge Agent 知识库平台

基于 **Spring Boot + Python FastAPI** 双引擎架构的企业级知识库平台。

## 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| API 网关 | Spring Boot 3.2 / Java 17 | 用户认证、权限管理、API 路由 |
| 智能引擎 | Python 3.11+ / FastAPI | RAG 流程编排、LLM 调用 |
| 向量模型 | BGE-M3 (BAAI/bge-m3) | 1024 维，中文优化 |
| 向量数据库 | Milvus 2.3.3 | 独立部署，IP 相似度检索 |
| LLM | DeepSeek Flash (deepseek-chat) | OpenAI 兼容接口 |
| 关系数据库 | MySQL 8.0 | 用户、知识库、文档元数据 |
| 缓存 | Redis 7 | 会话存储、Agent 状态 |
| 部署 | Docker Compose | 一键编排所有中间件 |

## 架构

```
用户请求
    |
    v
Spring Boot (:8080)          -- JWT 认证 / 权限 / API 路由
    |
    +-- /api/auth/*  -->  MySQL (用户认证)
    +-- /api/kb/*   -->  MySQL (知识库 CRUD)
    +-- /api/docs/* -->  MySQL (文档查询)
    +-- /api/rag/*  -->  Python Agent (:8000)
                             |
                             +-- BGE-M3 Embedding
                             +-- Milvus 向量检索
                             +-- Reranker 重排序
                             +-- DeepSeek 生成回答
```

## 快速启动

### 前置条件

- Docker Desktop (Windows/Mac) 或 Docker Engine (Linux)
- Python 3.11+
- Java 17 + Maven 3.8+
- 8GB+ 可用内存（Milvus 需要约 4GB）

### 一键启动 (Windows)

```batch
# 在项目根目录双击运行
start.bat
# 选择 [6] 一键全启动
```

### 手动启动

```bash
# 1. 启动中间件
docker-compose up -d

# 2. 等待 MySQL 就绪 (约 15 秒)，然后:
#    检查 MySQL 是否初始化完成
docker logs ka-mysql | tail -20

# 3. 启动 Python Agent
cd agent
pip install -r requirements.txt
cp .env.example .env          # 编辑 .env 填入 DeepSeek API Key
python main.py

# 4. 文档入库 (首次启动后执行一次)
python scripts/ingest_documents.py --clear

# 5. 启动 Spring Boot (新终端)
cd backend
mvn spring-boot:run
```

### 默认账户

| 用户名 | 密码 | 角色 | 权限 |
|--------|------|------|------|
| admin | admin123 | ADMIN | 全部知识库管理权限 |
| reader | reader123 | USER | 知识库只读权限 |

## API 文档

### 认证接口

```bash
# 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 响应
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOi...",
    "tokenType": "Bearer",
    "userId": 1,
    "username": "admin",
    "role": "ADMIN"
  }
}
```

### 知识库接口

```bash
# 获取可访问的知识库列表
curl http://localhost:8080/api/kb \
  -H "Authorization: Bearer <token>"

# 获取知识库详情
curl http://localhost:8080/api/kb/1 \
  -H "Authorization: Bearer <token>"
```

### 文档接口

```bash
# 获取知识库下的文档列表
curl http://localhost:8080/api/docs/kb/1 \
  -H "Authorization: Bearer <token>"

# 全文关键词搜索
curl "http://localhost:8080/api/docs/kb/1/search?keyword=架构" \
  -H "Authorization: Bearer <token>"
```

### RAG 问答接口

```bash
# 完整 RAG 问答 (检索 + 生成)
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "question": "系统的架构是什么样的？",
    "kbNames": ["技术文档库"]
  }'

# 纯向量检索 (仅检索，不生成)
curl -X POST http://localhost:8080/api/rag/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "question": "数据库设计规范",
    "kbNames": ["技术文档库"],
    "topK": 5
  }'
```

### Python Agent 直接调用

```bash
# Agent 健康检查
curl http://localhost:8000/

# Swagger 文档
# 浏览器打开: http://localhost:8000/docs

# RAG 查询
curl -X POST http://localhost:8000/api/v1/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question":"系统采用什么架构？","kb_names":["技术文档库"]}'
```

## 项目结构

```
g:\Knowledge Agent/
├── docker-compose.yml              # MySQL + Redis + Milvus(etcd+minio) 编排
├── start.bat                       # Windows 一键启动脚本
├── sql/
│   └── init.sql                    # 数据库初始化 DDL + 种子数据
│
├── backend/                        # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/ka/
│       ├── KaApplication.java      # 应用入口
│       ├── entity/                 # JPA 实体
│       │   ├── User.java           # 用户
│       │   ├── KnowledgeBase.java  # 知识库
│       │   ├── Document.java       # 文档
│       │   └── Permission.java     # 权限
│       ├── repository/             # JPA Repository
│       ├── dto/                    # 数据传输对象
│       ├── service/                # 业务逻辑层
│       ├── controller/             # REST 控制器
│       │   ├── AuthController.java           # 认证
│       │   ├── KnowledgeBaseController.java  # 知识库
│       │   ├── DocumentController.java       # 文档
│       │   └── RagController.java            # RAG 代理
│       ├── client/
│       │   └── AgentClient.java    # Python Agent HTTP 客户端
│       └── config/                 # 配置
│           ├── SecurityConfig.java # Spring Security + JWT
│           ├── JwtUtil.java        # JWT 工具类
│           ├── JwtAuthFilter.java  # JWT 认证过滤器
│           └── GlobalExceptionHandler.java
│
└── agent/                          # Python 智能引擎
    ├── main.py                     # FastAPI 入口
    ├── config.py                   # 配置管理 (环境变量)
    ├── requirements.txt            # Python 依赖
    ├── .env.example                # 环境变量模板
    ├── api/
    │   └── routes.py               # RAG API 路由
    ├── core/
    │   ├── query_processor.py      # 查询预处理 (分词/扩展)
    │   ├── retriever.py            # 向量检索
    │   ├── reranker.py             # 结果重排序
    │   └── generator.py            # DeepSeek 回答生成
    ├── embedding/
    │   └── bge_embedder.py         # BGE-M3 向量化
    ├── store/
    │   ├── milvus_client.py        # Milvus 客户端
    │   └── session_store.py        # Redis 会话管理
    ├── models/
    │   └── schemas.py              # Pydantic 模型
    └── scripts/
        ├── generate_bcrypt.py      # BCrypt 密码生成
        └── ingest_documents.py     # 文档入库脚本
```

## 核心流程

### RAG 问答流程

```
1. QueryProcessor   查询预处理 → 分词、关键词提取、查询扩展
2. BGEEmbedder      查询向量化 → encode_query("为这个句子生成表示...")
3. MilvusClient     向量检索   → IP 相似度 + nprobe=16
4. Reranker         结果重排序 → 按 score 降序，截断 top-k
5. Generator        LLM 生成   → DeepSeek Flash + RAG Prompt + 上下文
```

### 文档入库流程

```
1. MySQL 读取文档  → fetch_documents(kb_id)
2. 文本分块        → chunk_text(text, 512, 64)
3. BGE-M3 向量化   → embedder.encode_documents(chunks)
4. Milvus 写入     → collection.insert(embeddings)
5. 创建索引        → IVF_FLAT + nlist=128
6. 更新 chunk_count → MySQL UPDATE
```

## 配置说明

### Python Agent (.env)

| 变量 | 默认值 | 说明 |
|------|--------|------|
| KA_DEEPSEEK_API_KEY | - | DeepSeek API Key（必填） |
| KA_DEEPSEEK_MODEL | deepseek-chat | LLM 模型名 |
| KA_EMBEDDING_MODEL | BAAI/bge-m3 | Embedding 模型 |
| KA_EMBEDDING_DEVICE | cpu | 推理设备 (cpu/cuda) |
| KA_MILVUS_HOST | localhost | Milvus 地址 |
| KA_RETRIEVAL_TOP_K | 5 | 检索返回数 |
| KA_RERANK_TOP_K | 3 | 重排序截断数 |

### Spring Boot (application.yml)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| jwt.secret | (固定密钥) | JWT 签名密钥，生产环境务必更换 |
| jwt.expiration | 86400000 | Token 有效期 (24h) |
| agent.base-url | http://localhost:8000 | Python Agent 地址 |

## 数据库设计

### ER 图

```
users ──1:N──> permissions ──N:1──> knowledge_bases
  │                                      │
  │                                      │ 1:N
  │                                      │
  └──────────────────────────────────> documents
```

### 表结构

| 表名 | 说明 | 核心字段 |
|------|------|----------|
| users | 用户 | id, username, password_hash, role |
| knowledge_bases | 知识库 | id, name, description, created_by, is_public |
| documents | 文档 | id, kb_id, title, content, file_type, doc_status, chunk_count |
| permissions | 权限 | id, user_id, kb_id, permission_type (READ/WRITE/ADMIN) |

### 权限模型

- **READ**: 可查看知识库及文档
- **WRITE**: 可编辑文档内容
- **ADMIN**: 可管理知识库（含权限分配）

## 运维命令

```bash
# 查看 Docker 容器状态
docker-compose ps

# 查看服务日志
docker logs ka-mysql    # MySQL
docker logs ka-redis    # Redis
docker logs ka-milvus   # Milvus

# 重新入库
cd agent
python scripts/ingest_documents.py --clear

# 只入库指定知识库
python scripts/ingest_documents.py --kb-id 1

# 预览模式
python scripts/ingest_documents.py --dry-run

# 生成密码哈希 (用于添加新用户)
python scripts/generate_bcrypt.py
```
