"""E2E 测试：端到端验证所有 P0 功能"""
import requests, json

BASE = "http://localhost:8080"

def test(name, resp, expect=200):
    ok = resp.status_code == expect
    mark = "✅" if ok else "❌"
    try:
        body = resp.json()
        msg = body.get("message", "")[:60]
    except:
        msg = resp.text[:60]
    print(f"  {mark} {name}: {resp.status_code} {msg}")
    return ok, body

print("=" * 50)
print("Knowledge Agent E2E 测试")
print("=" * 50)

# 1. Health
print("\n--- 1. Health ---")
r = requests.get(f"{BASE}/api/health")
test("Health check", r)

# 2. Login
print("\n--- 2. Login ---")
r = requests.post(f"{BASE}/api/auth/login", json={"username":"admin","password":"admin123"})
ok, body = test("Admin login", r)
token = body.get("data", {}).get("token", "") if ok else ""
headers = {"Authorization": f"Bearer {token}"}

# 3. Weak password
print("\n--- 3. Password strength ---")
r = requests.post(f"{BASE}/api/admin/users", json={"username":"e2e_pw_test","password":"123"}, headers=headers)
test("Weak PW rejected", r, 400)

r = requests.post(f"{BASE}/api/admin/users", json={"username":"e2e_pw_test","password":"StrongP@ss1"}, headers=headers)
test("Strong PW accepted", r)

# 4. User operations
print("\n--- 4. User management ---")
r = requests.post(f"{BASE}/api/admin/users", json={"username":"e2e_del","password":"StrongP@ss1"}, headers=headers)
ok, _ = test("Create user e2e_del", r)

# Login as new user
r = requests.post(f"{BASE}/api/auth/login", json={"username":"e2e_del","password":"StrongP@ss1"})
test("e2e_del login", r)

# Delete user (get ID from list)
r = requests.get(f"{BASE}/api/admin/users", headers=headers)
users = r.json().get("data", [])
target = next((u for u in users if u["username"] == "e2e_del"), None)
if target:
    uid = target["id"]
    r = requests.delete(f"{BASE}/api/admin/users/{uid}", headers=headers)
    test(f"Delete e2e_del (id={uid})", r)

# 5. RAG + Memory
print("\n--- 5. RAG + Memory ---")
sid = "e2e-mem-test"
r = requests.post(f"{BASE}/api/rag/query", 
    json={"question":"我叫李四，喜欢红色","kbNames":[],"sessionId":sid}, headers=headers)
ok, body = test("RAG query", r)

r = requests.post(f"{BASE}/api/rag/query",
    json={"question":"我喜欢什么颜色","kbNames":[],"sessionId":sid}, headers=headers)
ok, body = test("Memory recall", r)
if ok:
    answer = body.get("data", {}).get("answer", "")
    has_red = "红" in answer or "red" in answer.lower()
    print(f"    {'✅' if has_red else '❌'} Color in answer: {answer[:60]}")

# 6. Audit log
print("\n--- 6. Audit log ---")
r = requests.get(f"{BASE}/api/admin/audit", headers=headers)
ok, body = test("Audit list", r)
if ok:
    logs = body.get("data", [])
    actions = set(l["action"] for l in logs)
    print(f"    Actions found: {actions}")

# 7. Rate limit
print("\n--- 7. Rate limit ---")
for i in range(1, 7):
    r = requests.post(f"{BASE}/api/auth/login", json={"username":"rl_test","password":"x"})
    msg = r.json().get("message", "")
    print(f"    #{i}: {msg[:50]}")
    if "锁定" in msg:
        print("    ✅ Rate limit activated")
        break

print("\n" + "=" * 50)
print("E2E 测试完成")
