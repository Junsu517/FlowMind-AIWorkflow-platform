# FlowMind 部署指南

本文记录 FlowMind 的生产部署方式：阿里云 ECS 单机部署，nginx 以路径前缀 `/fm` 与同机其他应用共存。以下所有密钥/密码均为占位符，请替换为你自己的值。

## 部署架构

```
用户浏览器
   │
nginx :80（静态托管 dist + gzip + immutable 缓存）
   ├── /fm/        → 前端静态文件（dist）
   └── /fm/api/    → 127.0.0.1:8080（Spring Boot）
                       │
        ┌──────────────┼───────────────┐
      MySQL 8        Redis         RabbitMQ（Docker）
       本机 3306      本机 6379       5672 / 管理台 15672
                                      Chroma（Docker, 8001）
```

服务器规格参考：2 核 2G（当前生产环境），各组件均为轻量配置。

## 1. 服务器准备

- 系统：Ubuntu 22.04+
- 安装：JDK 17、MySQL 8、Redis、Nginx、Docker（RabbitMQ 与 Chroma 用容器跑）
- 安全组：对外开放 80/443；3306/6379/5672/8001 仅本机访问（bind 127.0.0.1 或不放行）

## 2. MySQL

```sql
CREATE DATABASE flowmind CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'flowmind'@'localhost' IDENTIFIED BY '<DB_PASSWORD>';
GRANT ALL PRIVILEGES ON flowmind.* TO 'flowmind'@'localhost';
```

导入表结构：

```bash
mysql -uflowmind -p flowmind < backend/src/main/resources/schema.sql
```

## 3. Redis

默认配置即可（本机 6379）。限流与分布式锁均通过 Lua 脚本执行，对 Redis 无额外要求。

## 4. RabbitMQ（Docker）

```bash
docker run -d --name rabbitmq --restart always \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=flowmind \
  -e RABBITMQ_DEFAULT_PASS='<RABBITMQ_PASSWORD>' \
  rabbitmq:3-management
```

## 5. Chroma（Docker，RAG 功能）

```bash
docker run -d --name chroma --restart always \
  -p 8001:8000 \
  chromadb/chroma
```

## 6. 后端部署

### 构建

```bash
cd backend
mvn clean package -DskipTests
# 产物：target/flowmind-backend-*.jar
```

### 环境变量文件 `/home/admin/flowmind.env`

```bash
DB_URL=jdbc:mysql://localhost:3306/flowmind?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USERNAME=flowmind
DB_PASSWORD=<DB_PASSWORD>
REDIS_HOST=localhost
REDIS_PORT=6379
JWT_SECRET=<32字节以上随机串>
DEEPSEEK_API_KEY=<DEEPSEEK_API_KEY>
EXECUTION_MODE=mqueue
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=flowmind
RABBITMQ_PASSWORD=<RABBITMQ_PASSWORD>
CHROMA_BASE_URL=http://127.0.0.1:8001
EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
EMBEDDING_API_KEY=<SILICONFLOW_API_KEY>
PUBLIC_PATH_PREFIX=/fm
```

### systemd 服务 `/etc/systemd/system/flowmind.service`

```ini
[Unit]
Description=FlowMind backend
After=network.target mysql.service redis-server.service

[Service]
User=admin
WorkingDirectory=/home/admin/flowmind
EnvironmentFile=/home/admin/flowmind.env
ExecStart=/usr/bin/java -jar /home/admin/flowmind/flowmind-backend.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now flowmind
systemctl status flowmind
```

## 7. 前端构建与部署

### 构建铁律（漏参数会导致线上整包 mock 模式）

```bash
MSYS_NO_PATHCONV=1 VITE_API_BASE=/fm/api VITE_USE_MOCK=false npm run build -- --base=/fm/
```

- `VITE_USE_MOCK=false` 必须显式传入（api/index.js 默认 mock=true）
- `MSYS_NO_PATHCONV=1` 防止 Git Bash 把 `/fm/api` 转换成 Windows 路径
- 部署后验证：curl 线上 chunk 文件，确认 mock 标志为关闭态且含 `fm/api`

### 上传

```bash
scp -r frontend/dist/* admin@<SERVER_IP>:/home/admin/flowmind/dist/
```

## 8. Nginx 配置要点

```nginx
server {
    listen 80;
    server_name <你的域名或IP>;

    # 前端静态 + 强缓存
    location /fm/assets/ {
        alias /home/admin/flowmind/dist/assets/;
        expires 1y;
        add_header Cache-Control "public, immutable";
        gzip_static on;
    }
    location /fm/ {
        alias /home/admin/flowmind/dist/;
        try_files $uri $uri/ /fm/index.html;
        gzip on;
        gzip_types text/css application/javascript application/json;
    }

    # API 反代（SSE 必须关缓冲）
    location /fm/api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_buffering off;          # SSE 流式输出必需
        proxy_read_timeout 300s;      # 长连接（SSE/轮询）
    }
}
```

注意：`proxy_buffering off` 是流式输出的前提，缺了它 SSE 会被缓冲成一次性吐出。

## 9. 部署验证清单

```bash
# 后端健康
curl http://127.0.0.1:8080/fm/api/auth/login -X POST -H "Content-Type: application/json" -d '{}'

# 注册与鉴权链路
curl -X POST http://<SERVER>/fm/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"test01","password":"test123456","email":"t@t.com"}'

# 前端可达 + 静态资源 gzip
curl -sI http://<SERVER>/fm/ | head -5
curl -sI http://<SERVER>/fm/assets/<某js> | grep -i content-encoding

# 契约机器验收（本地跑）
python docs/contract_check.py http://<SERVER>/fm
```

## 10. 常用运维

```bash
systemctl restart flowmind            # 重启后端
journalctl -u flowmind -f             # 看日志
docker ps                             # RabbitMQ / Chroma 状态
docker restart rabbitmq chroma
du -sh /home/admin/flowmind/dist      # 前端产物大小
```

升级流程：备份旧 jar（`cp flowmind-backend.jar flowmind-backend.jar.bak-$(date +%s)`）→ 替换 jar → `systemctl restart flowmind` → 跑契约脚本。前端：构建 → 上传 dist → 无需重启服务。

## 安全备忘

- MySQL / Redis / RabbitMQ / Chroma 全部只监听 127.0.0.1
- `.env` / `flowmind.env` 权限 `chmod 600`，绝不进 git
- API Key 仅在创建时明文展示一次，库中存哈希
