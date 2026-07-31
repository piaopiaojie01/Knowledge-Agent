from pymilvus import connections, Collection
from sentence_transformers import SentenceTransformer
import numpy as np

model = SentenceTransformer('BAAI/bge-large-zh-v1.5', device='cuda')

c = connections.connect(host='localhost', port=19530)
col = Collection('knowledge_agent_docs')
col.load()

# Get all 2644 chunks
r = col.query(expr='id > 0', output_fields=['kb_name','content'], limit=3000)
print(f'Total: {len(r)}')

# Encode query
q = '欲望心理学的作者是谁'
qv = model.encode(q, normalize_embeddings=True).tolist()

# Search
hits = col.search([qv], 'embedding', {'metric_type':'IP','params':{'nprobe':16}}, limit=40,
                  output_fields=['content'])
for i, h in enumerate(hits[0]):
    txt = h.entity.get('content','')
    has_author = '作者' in txt or '华生' in txt
    marker = ' ★作者块' if has_author else ''
    print(f'  #{i+1} score={h.score:.4f} | {txt[:60]}{marker}')
