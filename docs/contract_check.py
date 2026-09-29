#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""FlowMind 契约校验脚本（第 1 周版，由统筹维护）

校验后端实现与 docs/API契约.md 的一致性。
用法:
    python docs/contract_check.py [BASE_URL]   # 默认 http://127.0.0.1:8080
退出码: 0 = 全部通过, 1 = 存在失败项
"""
import json
import random
import string
import sys
import time

import requests

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
API = BASE + "/api"

failures = []


def check(name, cond, detail=""):
    if cond:
        print(f"  [PASS] {name}")
    else:
        failures.append(name)
        print(f"  [FAIL] {name} | {detail}")


def has_fields(obj, fields):
    return all(f in obj for f in fields)


def rand_suffix():
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=6))


def expect_error(resp, code_name):
    try:
        body = resp.json()
        return body.get("code") == code_name
    except Exception:
        return False


def main():
    ua, ea = f"ca_{rand_suffix()}", f"ca_{rand_suffix()}@test.com"
    ub, eb = f"cb_{rand_suffix()}", f"cb_{rand_suffix()}@test.com"
    pw = "test123456"

    print("== 1. 认证模块 ==")

    r = requests.post(f"{API}/auth/register", json={"username": ua, "password": pw, "email": ea}, timeout=10)
    check("register 201", r.status_code == 201, f"got {r.status_code} {r.text[:100]}")
    data = r.json() if r.status_code == 201 else {}
    check("register 返回 token+user", has_fields(data, ["token", "user"]) and has_fields(data.get("user", {}), ["id", "username", "email", "role", "createdAt"]), str(data)[:120])
    token_a = data.get("token", "")

    r = requests.post(f"{API}/auth/register", json={"username": ua, "password": pw, "email": ea}, timeout=10)
    check("重复注册 400 USERNAME_EXISTS", r.status_code == 400 and expect_error(r, "USERNAME_EXISTS"), f"got {r.status_code} {r.text[:100]}")

    r = requests.post(f"{API}/auth/register", json={"username": ub, "password": pw, "email": eb}, timeout=10)
    check("注册第二个用户 201", r.status_code == 201, f"got {r.status_code}")
    token_b = r.json().get("token", "") if r.status_code == 201 else ""

    r = requests.post(f"{API}/auth/login", json={"username": ua, "password": "wrongpass"}, timeout=10)
    check("错误密码 401 BAD_CREDENTIALS", r.status_code == 401 and expect_error(r, "BAD_CREDENTIALS"), f"got {r.status_code} {r.text[:100]}")

    r = requests.post(f"{API}/auth/login", json={"username": ua, "password": pw}, timeout=10)
    check("登录 200", r.status_code == 200, f"got {r.status_code} {r.text[:100]}")
    token_a = r.json().get("token", token_a) if r.status_code == 200 else token_a
    if token_a:
        import base64
        try:
            header = json.loads(base64.urlsafe_b64decode(token_a.split(".")[0] + "=="))
            alg_ok = header.get("alg") == "HS256"
        except Exception:
            alg_ok = False
        check("JWT 签名算法 HS256", alg_ok, f"token header: {header if 'header' in dir() else '解析失败'}")

    r = requests.get(f"{API}/auth/me", headers={"Authorization": f"Bearer {token_a}"}, timeout=10)
    check("me 200 且字段齐全", r.status_code == 200 and has_fields(r.json(), ["id", "username", "email", "role", "createdAt"]), f"got {r.status_code} {r.text[:100]}")

    r = requests.get(f"{API}/workflows", timeout=10)
    check("未带 token 401 UNAUTHORIZED", r.status_code == 401 and expect_error(r, "UNAUTHORIZED"), f"got {r.status_code} {r.text[:100]}")

    print("== 2. 工作流模块 ==")
    h_a = {"Authorization": f"Bearer {token_a}"}
    h_b = {"Authorization": f"Bearer {token_b}"}

    r = requests.post(f"{API}/workflows", headers=h_a, json={"name": "契约测试流", "description": "脚本自动创建"}, timeout=10)
    check("创建 201 且字段齐全", r.status_code == 201 and has_fields(r.json(), ["id", "name", "description", "status", "createdAt", "updatedAt"]), f"got {r.status_code} {r.text[:120]}")
    wf_id = r.json().get("id") if r.status_code == 201 else None

    graph = {
        "nodes": [
            {"nodeKey": "input_1", "type": "input", "name": "输入",
             "config": {"fields": [{"key": "city", "label": "城市", "type": "text", "default": "南昌"}]},
             "positionX": 0, "positionY": 0},
            {"nodeKey": "cond_1", "type": "condition", "name": "判断",
             "config": {"rules": [{"left": "{{input.city}}", "operator": "equals", "right": "南昌", "handle": "nc"}], "default_handle": "other"},
             "positionX": 100, "positionY": 0},
            {"nodeKey": "http_1", "type": "http", "name": "天气",
             "config": {"tool_id": 1, "params": {"city": "{{input.city}}"}, "output_key": "data"},
             "positionX": 200, "positionY": 0},
            {"nodeKey": "llm_1", "type": "llm", "name": "AI 建议",
             "config": {"model": "deepseek-v4-flash", "prompt": "给 {{input.city}} 穿衣建议", "temperature": 0.7, "output_key": "text"},
             "positionX": 200, "positionY": 100},
        ],
        "edges": [
            {"sourceNodeKey": "input_1", "targetNodeKey": "cond_1", "sourceHandle": None},
            {"sourceNodeKey": "cond_1", "targetNodeKey": "http_1", "sourceHandle": "nc"},
            {"sourceNodeKey": "cond_1", "targetNodeKey": "llm_1", "sourceHandle": "other"},
        ],
    }
    r = requests.put(f"{API}/workflows/{wf_id}/graph", headers=h_a, json=graph, timeout=10)
    check("保存合法图 200 {saved:true}", r.status_code == 200 and r.json().get("saved") is True, f"got {r.status_code} {r.text[:120]}")

    r = requests.get(f"{API}/workflows/{wf_id}/graph", headers=h_a, timeout=10)
    ok = r.status_code == 200 and len(r.json().get("nodes", [])) == 4 and len(r.json().get("edges", [])) == 3
    check("读图 200 节点边数量一致", ok, f"got {r.status_code} {r.text[:120]}")

    cyclic = {
        "nodes": [
            {"nodeKey": "input_1", "type": "input", "name": "输入", "config": {"fields": []}, "positionX": 0, "positionY": 0},
            {"nodeKey": "a", "type": "llm", "name": "A", "config": {"model": "m", "prompt": "p", "temperature": 0, "output_key": "o"}, "positionX": 100, "positionY": 0},
            {"nodeKey": "b", "type": "llm", "name": "B", "config": {"model": "m", "prompt": "p", "temperature": 0, "output_key": "o"}, "positionX": 200, "positionY": 0},
        ],
        "edges": [
            {"sourceNodeKey": "input_1", "targetNodeKey": "a", "sourceHandle": None},
            {"sourceNodeKey": "a", "targetNodeKey": "b", "sourceHandle": None},
            {"sourceNodeKey": "b", "targetNodeKey": "a", "sourceHandle": None},
        ],
    }
    r = requests.put(f"{API}/workflows/{wf_id}/graph", headers=h_a, json=cyclic, timeout=10)
    check("环状图拒绝 400 INVALID_GRAPH", r.status_code == 400 and expect_error(r, "INVALID_GRAPH"), f"got {r.status_code} {r.text[:120]}")

    no_input = {
        "nodes": [
            {"nodeKey": "llm_1", "type": "llm", "name": "AI", "config": {"model": "m", "prompt": "p", "temperature": 0, "output_key": "o"}, "positionX": 0, "positionY": 0},
        ],
        "edges": [],
    }
    r = requests.put(f"{API}/workflows/{wf_id}/graph", headers=h_a, json=no_input, timeout=10)
    check("缺 input 节点拒绝 400 INVALID_GRAPH", r.status_code == 400 and expect_error(r, "INVALID_GRAPH"), f"got {r.status_code} {r.text[:120]}")

    dup_edge = {
        "nodes": [
            {"nodeKey": "input_1", "type": "input", "name": "输入", "config": {"fields": []}, "positionX": 0, "positionY": 0},
            {"nodeKey": "llm_1", "type": "llm", "name": "AI", "config": {"model": "m", "prompt": "p", "temperature": 0, "output_key": "o"}, "positionX": 100, "positionY": 0},
        ],
        "edges": [
            {"sourceNodeKey": "input_1", "targetNodeKey": "llm_1", "sourceHandle": None},
            {"sourceNodeKey": "input_1", "targetNodeKey": "llm_1", "sourceHandle": None},
        ],
    }
    r = requests.put(f"{API}/workflows/{wf_id}/graph", headers=h_a, json=dup_edge, timeout=10)
    check("重复边拒绝 400 INVALID_GRAPH", r.status_code == 400 and expect_error(r, "INVALID_GRAPH"), f"got {r.status_code} {r.text[:120]}")

    unreachable = {
        "nodes": [
            {"nodeKey": "input_1", "type": "input", "name": "输入", "config": {"fields": []}, "positionX": 0, "positionY": 0},
            {"nodeKey": "llm_1", "type": "llm", "name": "AI", "config": {"model": "m", "prompt": "p", "temperature": 0, "output_key": "o"}, "positionX": 100, "positionY": 0},
            {"nodeKey": "llm_2", "type": "llm", "name": "孤儿", "config": {"model": "m", "prompt": "p", "temperature": 0, "output_key": "o"}, "positionX": 200, "positionY": 0},
        ],
        "edges": [
            {"sourceNodeKey": "input_1", "targetNodeKey": "llm_1", "sourceHandle": None},
        ],
    }
    r = requests.put(f"{API}/workflows/{wf_id}/graph", headers=h_a, json=unreachable, timeout=10)
    check("不可达节点拒绝 400 INVALID_GRAPH", r.status_code == 400 and expect_error(r, "INVALID_GRAPH"), f"got {r.status_code} {r.text[:120]}")

    r = requests.put(f"{API}/workflows/{wf_id}/graph", headers=h_a, json=graph, timeout=10)
    check("重新保存合法图成功", r.status_code == 200, f"got {r.status_code}")

    r = requests.get(f"{API}/workflows", headers=h_a, params={"page": 1, "size": 10}, timeout=10)
    body = r.json() if r.status_code == 200 else {}
    items = body.get("items", [])
    mine = [w for w in items if w.get("id") == wf_id]
    ok = r.status_code == 200 and has_fields(body, ["items", "total", "page", "size"]) and mine and mine[0].get("nodeCount") == 4
    check("列表字段齐全且 nodeCount=4", ok, f"got {r.status_code} {r.text[:150]}")

    r = requests.get(f"{API}/workflows/{wf_id}", headers=h_b, timeout=10)
    check("他人读工作流 403", r.status_code == 403, f"got {r.status_code} {r.text[:100]}")

    r = requests.delete(f"{API}/workflows/{wf_id}", headers=h_b, timeout=10)
    check("他人删工作流 403", r.status_code == 403, f"got {r.status_code} {r.text[:100]}")

    r = requests.delete(f"{API}/workflows/{wf_id}", headers=h_a, timeout=10)
    check("本人删除 204", r.status_code == 204, f"got {r.status_code} {r.text[:100]}")

    print("== 3. 工具模块 ==")
    r = requests.get(f"{API}/tools", headers=h_a, timeout=10)
    body = r.json() if r.status_code == 200 else {}
    items = body.get("items", [])
    ok = r.status_code == 200 and len(items) >= 2 and all(has_fields(t, ["id", "name", "displayName", "description", "method", "paramsSchema"]) for t in items)
    check("tools 列表字段齐全且 ≥2 条", ok, f"got {r.status_code} {r.text[:150]}")

    print("== 4. 执行模块 ==")
    exec_graph = {
        "nodes": [
            {"nodeKey": "input_1", "type": "input", "name": "输入",
             "config": {"fields": [{"key": "city", "label": "城市", "type": "text"}]},
             "positionX": 0, "positionY": 0},
            {"nodeKey": "llm_1", "type": "llm", "name": "AI",
             "config": {"model": "deepseek-flash", "prompt": "Reply with exactly one word: hello", "temperature": 0, "output_key": "text"},
             "positionX": 100, "positionY": 0},
        ],
        "edges": [{"sourceNodeKey": "input_1", "targetNodeKey": "llm_1", "sourceHandle": None}],
    }
    r = requests.post(f"{API}/workflows", headers=h_a, json={"name": "exec-check", "description": "script"}, timeout=10)
    exec_wf = r.json().get("id") if r.status_code == 201 else None
    check("执行测试工作流创建 201", exec_wf is not None, f"got {r.status_code}")
    r = requests.put(f"{API}/workflows/{exec_wf}/graph", headers=h_a, json=exec_graph, timeout=10)
    check("执行测试图保存 200", r.status_code == 200, f"got {r.status_code}")

    r = requests.post(f"{API}/workflows/{exec_wf}/execute", headers=h_a, json={"input": {}}, timeout=10)
    check("缺必填字段 400 INVALID_INPUT", r.status_code == 400 and expect_error(r, "INVALID_INPUT"), f"got {r.status_code} {r.text[:120]}")

    r = requests.post(f"{API}/workflows/{exec_wf}/execute", headers=h_a, json={"input": {"city": "Nanchang"}}, timeout=10)
    check("execute 202 返回 executionId", r.status_code == 202 and "executionId" in r.json(), f"got {r.status_code} {r.text[:120]}")
    exec_id = r.json().get("executionId") if r.status_code == 202 else None

    if exec_id:
        terminal = None
        for _ in range(30):
            time.sleep(3)
            r = requests.get(f"{API}/executions/{exec_id}", headers=h_a, timeout=10)
            if r.status_code != 200:
                break
            detail = r.json()
            if detail.get("status") in ("success", "failed", "cancelled"):
                terminal = detail
                break
        check("执行到达终态", terminal is not None, f"30 次轮询后仍非终态")
        if terminal:
            check("执行详情字段齐全", has_fields(terminal, ["id", "workflowId", "workflowName", "status", "input", "output", "totalTokens", "totalCost", "durationMs", "startedAt"]), str(terminal)[:150])
            check("执行成功且 token>0", terminal.get("status") == "success" and terminal.get("totalTokens", 0) > 0, f"status={terminal.get('status')} tokens={terminal.get('totalTokens')}")
        r = requests.get(f"{API}/executions/{exec_id}/logs", headers=h_a, timeout=10)
        body = r.json() if r.status_code == 200 else {}
        log_items = body.get("items", [])
        ok = r.status_code == 200 and len(log_items) >= 2 and all(has_fields(n, ["nodeKey", "nodeName", "nodeType", "status", "input", "output", "tokens", "durationMs", "errorMessage"]) for n in log_items)
        check("节点日志字段齐全且 ≥2 条", ok, f"got {r.status_code} {r.text[:150]}")
        r = requests.get(f"{API}/executions", headers=h_a, params={"page": 1, "size": 10}, timeout=10)
        body = r.json() if r.status_code == 200 else {}
        ok = r.status_code == 200 and has_fields(body, ["items", "total", "page", "size"]) and any(e.get("id") == exec_id for e in body.get("items", []))
        check("执行历史列表含本次执行", ok, f"got {r.status_code}")

    print("== 5. 知识库模块（阶梯 5 RAG） ==")
    r = requests.post(f"{API}/knowledge-bases", headers=h_a, json={"name": "contract-check-kb", "description": "script"}, timeout=10)
    kb = r.json() if r.status_code == 201 else {}
    kb_id = kb.get("id")
    check("创建知识库 201 且字段齐全", r.status_code == 201 and has_fields(kb, ["id", "name", "description", "createdAt"]), f"got {r.status_code} {r.text[:120]}")

    r = requests.get(f"{API}/knowledge-bases", headers=h_a, params={"page": 1, "size": 10}, timeout=10)
    kb_items = (r.json() or {}).get("items", [])
    check("知识库列表含新建库且带 documentCount", r.status_code == 200 and any(i.get("id") == kb_id and "documentCount" in i for i in kb_items), f"got {r.status_code} {r.text[:150]}")

    r = requests.get(f"{API}/knowledge-bases/{kb_id}/documents", headers=h_a, timeout=10)
    check("空文档列表 200", r.status_code == 200, f"got {r.status_code} {r.text[:120]}")

    import tempfile
    import os
    rag_ok = False
    doc_id = None
    if kb_id:
        # 非支持类型：不依赖 embedding，任何环境都应 400
        with tempfile.NamedTemporaryFile("wb", suffix=".exe", delete=False) as f:
            f.write(b"MZ fake exe for contract check")
            exe_path = f.name
        with open(exe_path, "rb") as f:
            r = requests.post(f"{API}/knowledge-bases/{kb_id}/documents", headers=h_a,
                              files={"file": ("fake.exe", f)}, timeout=15)
        os.unlink(exe_path)
        check("上传 .exe 400 DOCUMENT_PARSE_ERROR", r.status_code == 400 and expect_error(r, "DOCUMENT_PARSE_ERROR"), f"got {r.status_code} {r.text[:120]}")

        # 真实 txt 上传：依赖 embedding + Chroma
        with tempfile.NamedTemporaryFile("w", suffix=".txt", encoding="utf-8", delete=False) as f:
            f.write(("FlowMind 契约检查：这是用于验证 RAG 知识库链路的中文测试文档。猫咪每天需要喂食两次，每次四十克猫粮。") * 12)
            txt_path = f.name
        with open(txt_path, "rb") as f:
            r = requests.post(f"{API}/knowledge-bases/{kb_id}/documents", headers=h_a,
                              files={"file": ("contract-check.txt", f)}, timeout=60)
        os.unlink(txt_path)
        if r.status_code == 502 and expect_error(r, "EMBEDDING_ERROR"):
            print("  (SKIP: embedding 未配置，跳过 RAG 上传/执行相关检查)")
        else:
            doc = r.json() if r.status_code == 201 else {}
            check("上传 txt 201 且 chunkCount≥1", r.status_code == 201 and doc.get("chunkCount", 0) >= 1, f"got {r.status_code} {r.text[:150]}")
            doc_id = doc.get("id")
            r = requests.get(f"{API}/knowledge-bases/{kb_id}/documents", headers=h_a, timeout=10)
            doc_items = (r.json() or {}).get("items", [])
            check("文档列表含上传文档", r.status_code == 200 and any(i.get("id") == doc_id for i in doc_items), f"got {r.status_code} {r.text[:150]}")
            rag_ok = doc_id is not None

        # RAG 执行链路（真实 embedding + Chroma + DeepSeek）
        if rag_ok:
            rag_graph = {
                "nodes": [
                    {"nodeKey": "input_1", "type": "input", "name": "输入",
                     "config": {"fields": [{"key": "q", "label": "问题", "type": "text", "default": ""}]},
                     "positionX": 0, "positionY": 0},
                    {"nodeKey": "llm_1", "type": "llm", "name": "RAG 回答",
                     "config": {"model": "deepseek-flash", "prompt": "猫咪每天喂几次？每次喂多少克？",
                                "temperature": 0, "output_key": "text", "knowledge_base_id": kb_id},
                     "positionX": 100, "positionY": 0},
                ],
                "edges": [{"sourceNodeKey": "input_1", "targetNodeKey": "llm_1", "sourceHandle": None}],
            }
            r = requests.post(f"{API}/workflows", headers=h_a, json={"name": "rag-check", "description": "script"}, timeout=10)
            rag_wf = r.json().get("id") if r.status_code == 201 else None
            requests.put(f"{API}/workflows/{rag_wf}/graph", headers=h_a, json=rag_graph, timeout=10)
            r = requests.post(f"{API}/workflows/{rag_wf}/execute", headers=h_a, json={"input": {}}, timeout=10)
            rag_exec = r.json().get("executionId") if r.status_code == 202 else None
            if rag_exec:
                terminal = None
                for _ in range(30):
                    time.sleep(3)
                    r = requests.get(f"{API}/executions/{rag_exec}", headers=h_a, timeout=10)
                    if r.status_code != 200:
                        break
                    detail = r.json()
                    if detail.get("status") in ("success", "failed", "cancelled"):
                        terminal = detail
                        break
                check("RAG 执行到达终态", terminal is not None, "30 次轮询后仍非终态")
                if terminal:
                    refs = (terminal.get("output") or {}).get("llm_1", {}).get("references")
                    check("RAG 执行成功且 references 非空", terminal.get("status") == "success" and isinstance(refs, list) and len(refs) > 0, f"status={terminal.get('status')} refs={str(refs)[:120]}")

    # 清理
    if doc_id:
        r = requests.delete(f"{API}/knowledge-bases/{kb_id}/documents/{doc_id}", headers=h_a, timeout=10)
        check("删除文档 204", r.status_code == 204, f"got {r.status_code}")
    if kb_id:
        r = requests.delete(f"{API}/knowledge-bases/{kb_id}", headers=h_a, timeout=15)
        check("删除知识库 204", r.status_code == 204, f"got {r.status_code} {r.text[:120]}")
        r = requests.get(f"{API}/knowledge-bases", headers=h_a, params={"page": 1, "size": 10}, timeout=10)
        kb_items = (r.json() or {}).get("items", [])
        check("知识库列表已无删除项", r.status_code == 200 and not any(i.get("id") == kb_id for i in kb_items), f"got {r.status_code}")

    print()
    if failures:
        print(f"结果: FAIL（{len(failures)} 项未通过）")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print("结果: ALL PASS")


if __name__ == "__main__":
    try:
        main()
    except requests.exceptions.ConnectionError:
        print(f"无法连接后端 {BASE}，请确认后端已启动（mvn spring-boot:run）")
        sys.exit(1)
