"""快速入库 - 用 bge-small 模型，智能分块"""
import os, sys, re
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import settings
from pymilvus import connections, utility, Collection

connections.connect(host="localhost", port=19530)
if utility.has_collection("knowledge_agent_docs"):
    Collection("knowledge_agent_docs").drop()
    print("dropped old collection")

settings.embedding_model = "BAAI/bge-small-zh-v1.5"
settings.embedding_dim = 512
print(f"using: {settings.embedding_model} dim={settings.embedding_dim}")

from embedding.bge_embedder import embedder
embedder._model = None
embedder.model
print("model loaded")

from store.milvus_client import milvus_client
milvus_client.connect()
milvus_client.ensure_collection()

import pymysql
conn = pymysql.connect(host="localhost", port=3306, user="ka_user",
                       password=os.getenv("DB_PASS", os.getenv("KA_MYSQL_PASSWORD", "")),
                       database="knowledge_agent", charset="utf8mb4")
with conn.cursor(pymysql.cursors.DictCursor) as c:
    c.execute("""SELECT d.id, d.title, d.content, kb.name as kb_name
                 FROM documents d JOIN knowledge_bases kb ON d.kb_id = kb.id
                 WHERE d.doc_status = 'ACTIVE'""")
    docs = c.fetchall()
conn.close()
print(f"got {len(docs)} docs")

total = 0
for doc in docs:
    content = doc["content"]
    if not content: continue

    # 预处理：合并PDF短行
    lines = content.split("\n")
    merged_lines, buf = [], []
    for line in lines:
        s = line.strip()
        if not s:
            if buf: merged_lines.append(" ".join(buf)); buf = []
            merged_lines.append("")
        elif len(s) < 80: buf.append(s)
        else:
            if buf: merged_lines.append(" ".join(buf)); buf = []
            merged_lines.append(s)
    if buf: merged_lines.append(" ".join(buf))
    text = "\n".join(merged_lines)

    # 分块
    chunks = []
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if not para: continue
        if len(para) <= 512: chunks.append(para)
        else:
            for sent in re.split(r"(?<=[。！？；])", para):
                s = sent.strip()
                if not s: continue
                if len(s) > 512:
                    for i in range(0, len(s), 448): chunks.append(s[i:i+512].strip())
                else: chunks.append(s)
    chunks = [c for c in chunks if len(c) >= 20]

    for chunk in chunks:
        emb = embedder.encode_documents([chunk])
        milvus_client.insert([{"doc_id": doc["id"], "kb_name": doc["kb_name"],
                               "title": doc["title"], "content": chunk[:4000],
                               "embedding": emb[0].tolist()}])
        total += 1
    print(f"  doc {doc['id']}: {doc['title'][:30]} ({len(chunks)} chunks)")

milvus_client.create_index_if_needed()
print(f"done: {total} chunks total")
