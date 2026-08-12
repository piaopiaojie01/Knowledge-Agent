import os, pymupdf, base64, requests, pymysql

path = r"C:\Users\admin\Downloads\欲望心理学.pdf"
doc = pymupdf.open(path)
text = "\n".join(page.get_text() for page in doc)
doc.close()
print(f"extracted {len(text)} chars")

conn = pymysql.connect(host="localhost", port=3306, user="ka_user",
                       password=os.getenv("DB_PASS", os.getenv("KA_MYSQL_PASSWORD", "")),
                       database="knowledge_agent", charset="utf8mb4")
with conn.cursor() as c:
    c.execute("DELETE FROM documents WHERE title LIKE '%%欲望%%'")
conn.commit(); conn.close()

from pymilvus import connections, Collection, utility
connections.connect(host="localhost", port=19530)
if utility.has_collection("knowledge_agent_docs"):
    Collection("knowledge_agent_docs").drop()

with open(path, "rb") as f:
    pdf_b64 = base64.b64encode(f.read()).decode()

r = requests.post("http://localhost:8000/api/v1/rag/ingest-pdf", json={
    "doc_id": 99, "title": "欲望心理学.pdf", "kb_name": "心里学", "pdf_base64": pdf_b64
})
print(r.json())
