# FlowMind · AI Workflow Platform

Build AI applications by dragging and dropping — no code required.

> Live demo: http://43.108.111.175/fm/ ｜ Source: https://gitee.com/junsu517/flowmind-ai-work-platform

FlowMind is a visual AI workflow platform inspired by Dify/Coze. Compose nodes — Input, LLM, Condition, HTTP — into a workflow graph on a canvas; the platform handles execution orchestration, streaming output, and usage analytics. Independently developed, deployed in production.

## Features

| Capability | Description |
|------------|-------------|
| Visual orchestration | Vue Flow canvas with Input / LLM / Condition / HTTP nodes; automatic graph validation (cycles, orphan nodes, duplicate edges) |
| Parallel execution engine | Independent nodes run concurrently; conditional branches route at runtime; node-level execution visualization |
| Function Calling | LLM invokes HTTP tools automatically in a multi-turn agent loop (DeepSeek streaming API) |
| RAG knowledge base | Upload documents for retrieval-augmented Q&A: chunking → embedding → semantic Top-K retrieval, answers with citations |
| Streaming output | SSE real-time token streaming with live node status |
| Reliable async execution | RabbitMQ-backed queue: publisher confirms, retries, dead-letter queue, tasks survive process restarts |
| Scheduled triggers | Cron-based automatic workflow execution (Quartz) |
| Publish as HTTP API | Turn any workflow into a callable API: key auth + rate limiting + built-in debugger |
| Analytics dashboard | 8 metrics (executions, success rate, tokens, cost...), 30-day trends, source distribution |

## Tech Stack

**Backend**: Java 17 · Spring Boot 3 · MyBatis-Plus · MySQL 8 · Redis · RabbitMQ · Quartz · JWT

**AI**: DeepSeek API (LLM, streaming via OkHttp) · SiliconFlow bge-m3 (embeddings) · Chroma (vector search)

**Frontend**: Vue 3 · Vue Flow · Element Plus · Pinia · ECharts · Vite

**Deployment**: Alibaba Cloud ECS · Nginx · systemd

## Architecture

```
Browser (Vue 3)
   │ HTTP / SSE
Nginx (static hosting + reverse proxy)
   └─ Spring Boot 3 (layered monolith)
        ├─ MySQL        workflow & execution data
        ├─ Redis        rate limiting / distributed locks
        ├─ RabbitMQ     async execution queue (reliable delivery)
        ├─ Quartz       scheduled triggers
        └─ DeepSeek / Chroma   LLM calls / vector search
```

## Quick Start (local)

### Requirements

- JDK 17+, MySQL 8, Redis, Node.js 18+
- RabbitMQ optional: without it the engine uses an in-process thread pool (all core features work); set `EXECUTION_MODE=mqueue` to enable queue execution
- RAG requires: Chroma (local port 8001) + a SiliconFlow API key

### Steps

1. **Database**: create database `flowmind`, import `backend/src/main/resources/schema.sql`

2. **Environment variables** (before starting the backend):

   | Variable | Required | Notes |
   |----------|----------|-------|
   | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | yes | MySQL connection (local defaults provided) |
   | `DEEPSEEK_API_KEY` | yes | required for LLM nodes |
   | `EMBEDDING_API_KEY` | for RAG | SiliconFlow embeddings |
   | `CHROMA_BASE_URL` | for RAG | default `http://127.0.0.1:8001` |
   | `RABBITMQ_HOST` etc. | for MQ mode | local defaults provided |

3. **Backend**:

   ```bash
   cd backend
   mvn spring-boot:run        # listens on 8080
   ```

4. **Frontend**:

   ```bash
   cd frontend
   npm install
   npm run dev                # http://localhost:5173, /api proxied to 8080
   ```

5. Open http://localhost:5173, register an account, and build your first workflow.

## Project Structure

```
flowmind/
├── backend/     Spring Boot 3 backend (engine/ = parallel execution engine core)
└── frontend/    Vue 3 + Vue Flow frontend
```

## Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for the production deployment guide (Alibaba Cloud ECS + Nginx + systemd, with build rules and verification checklist).

## Performance & Quality

- JMeter load test: 10 concurrent, p95 240ms on the submit path
- 56+ automated API contract checks, all passing against production
- 48 unit tests covering engine edge cases (merge nodes, conditional routing, fail-fast)

## Dev Journal

A first-person development journal (in Chinese) covering all nine iterations — the "why" behind each decision and the pitfalls we hit — see [`tutorial/`](tutorial/).

## License

[Mulan Permissive Software License, Version 2 (MulanPSL-2.0)](http://license.coscl.org.cn/MulanPSL2)
