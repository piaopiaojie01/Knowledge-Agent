#!/bin/bash
set -e
# Knowledge Agent Linux 一键部署脚本
# 用法: chmod +x deploy.sh && sudo ./deploy.sh

KA_HOME="/opt/knowledge-agent"
KA_USER="ka"
GREEN='\033[0;32m'
NC='\033[0m'

# 加载生产密钥（如存在 /etc/ka.env：DB_PASS / JWT_SECRET / KA_INTERNAL_API_KEY / KA_DEEPSEEK_API_KEY / REDIS_PASS）
if [ -f /etc/ka.env ]; then
    set -a
    . /etc/ka.env
    set +a
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Knowledge Agent Linux 部署${NC}"
echo -e "${GREEN}========================================${NC}"

# === 1. 创建用户和目录 ===
echo "[1/6] 创建用户和目录..."
if ! id -u $KA_USER >/dev/null 2>&1; then
    useradd -r -s /bin/false $KA_USER
fi
mkdir -p /var/log/ka
chown -R $KA_USER:$KA_USER /var/log/ka
chown -R $KA_USER:$KA_USER $KA_HOME

# === 2. Docker 基础设施 ===
echo "[2/6] 启动 Docker 服务..."
cd $KA_HOME
docker compose up -d
sleep 5

# 等待 MySQL 就绪
echo "  等待 MySQL..."
for i in {1..30}; do
    if docker compose exec -T mysql mysqladmin ping -h localhost --silent 2>/dev/null; then
        echo "  MySQL 已就绪"
        break
    fi
    sleep 2
done

# === 3. Vue 前端构建 ===
echo "[3/7] 构建 Vue 前端..."
cd $KA_HOME/frontend
if command -v npm &>/dev/null; then
    npm install --silent
    npm run build
    mkdir -p /var/www/ka
    cp -r dist/* /var/www/ka/
    chown -R www-data:www-data /var/www/ka 2>/dev/null || chown -R nginx:nginx /var/www/ka 2>/dev/null || true
    echo "  前端构建完成 → /var/www/ka"
else
    echo "  跳过 (未安装 Node.js)"
fi

# === 4. Python Agent venv ===
echo "[4/7] 配置 Python Agent..."
cd $KA_HOME/agent
if [ ! -d ".venv" ]; then
    python3 -m venv .venv
fi
.venv/bin/pip install -r requirements.txt -q
chown -R $KA_USER:$KA_USER .venv data/

# === 5. Spring Boot 构建 ===
echo "[5/7] 构建 Spring Boot..."
cd $KA_HOME/backend
# 确保有 JDK 17
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
mvn package -DskipTests -q
chown $KA_USER:$KA_USER target/*.jar

# === 6. systemd 服务 ===
echo "[6/7] 安装 systemd 服务..."
cp $KA_HOME/deploy/ka-backend.service /etc/systemd/system/
cp $KA_HOME/deploy/ka-agent.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable ka-backend ka-agent
systemctl start ka-backend ka-agent
echo "  服务已启动"

# === 7. Nginx 反代 ===
echo "[7/7] 配置 Nginx..."
if command -v nginx &>/dev/null; then
    cp $KA_HOME/deploy/nginx.conf /etc/nginx/sites-available/ka
    ln -sf /etc/nginx/sites-available/ka /etc/nginx/sites-enabled/
    
    # 生成自签证书（如果没有正式证书）
    if [ ! -f /etc/nginx/ssl/ka.crt ]; then
        mkdir -p /etc/nginx/ssl
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
            -keyout /etc/nginx/ssl/ka.key \
            -out /etc/nginx/ssl/ka.crt \
            -subj "/CN=knowledge-agent.your-company.com"
    fi
    
    nginx -t && systemctl reload nginx
    echo "  Nginx 已配置"
else
    echo "  跳过 (未安装 Nginx)，直接访问 http://localhost:8080"
fi


# === 每日备份 cron ===
chmod +x $KA_HOME/deploy/backup-db.sh
chmod +x $KA_HOME/deploy/restore-db.sh
(crontab -l 2>/dev/null | grep -v backup-db; echo "0 3 * * * $KA_HOME/deploy/backup-db.sh >> /var/log/ka/backup.log 2>&1") | crontab -
echo "  每日备份 cron 已设置 (凌晨3点)"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  管理命令:"
echo "    systemctl status ka-backend ka-agent"
echo "    journalctl -u ka-backend -f"
echo "    journalctl -u ka-agent -f"
echo ""
echo "  备份目录: /opt/knowledge-agent/backups (保留30天)"
echo "  手动备份: $KA_HOME/deploy/backup-db.sh"
echo "  恢复: $KA_HOME/deploy/restore-db.sh <备份文件>"
echo "  访问地址: https://knowledge-agent.your-company.com"
echo "  admin / admin123"
