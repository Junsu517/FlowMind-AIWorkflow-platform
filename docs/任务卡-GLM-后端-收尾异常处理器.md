# 任务卡：GLM 后端 · 收尾迭代（GlobalExceptionHandler 修复）

> 把本任务卡 + docs/API契约.md + docs/进度同步.md 一起粘贴给你。
> 阶梯 4 已验收，本轮**不许动**：LlmNodeExecutor、HttpToolInvoker、engine 包、任何执行链路代码。
> 本轮只修一个 bug：GlobalExceptionHandler。

## 你的领土

- 允许：backend/ 下除 com.flowmind.engine 包以外（本轮实际只应碰 common/GlobalExceptionHandler.java 及测试）
- 禁止：改 engine 包、frontend、docs；运行 git；私自改契约

## 背景（统筹已实测，服务器日志为证）

**Bug**：当请求带 `Accept: text/event-stream`（SSE 流订阅）且鉴权失败时：

1. AuthInterceptor 抛 BusinessException（如"缺少 Bearer token" / TOKEN_EXPIRED）
2. `GlobalExceptionHandler#handleBusiness` 尝试返回 JSON 错误体
3. Spring 内容协商发现 Accept 只接受 event-stream，抛 `HttpMediaTypeNotAcceptableException: No acceptable representation`
4. **异常处理器自己崩溃 → 返回空 body 的 HTTP 500**（应该返回 401 + JSON 错误码）

服务器 journalctl 实测日志：
```
WARN  .m.m.a.ExceptionHandlerExceptionResolver : Failure in @ExceptionHandler com.flowmind.common.GlobalExceptionHandler#handleBusiness(BusinessException)
org.springframework.web.HttpMediaTypeNotAcceptableException: No acceptable representation
ERROR o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() ... com.flowmind.common.BusinessException: 缺少 Bearer token
```

**影响**：前端 SSE 订阅鉴权失败时收到空 500，无法区分"登录过期"和"SSE 坏了"，只能显示笼统的降级提示。

## 任务清单

### A. GlobalExceptionHandler 改造：直接写响应，绕过内容协商

对 `handleBusiness`（以及你检查后发现同样会炸的同类 handler，如通用 Exception handler）：

- 不再返回带 `@ResponseBody` 的 JSON 对象，改为**直接写 `HttpServletResponse`**：
  ```java
  @ExceptionHandler(BusinessException.class)
  public void handleBusiness(BusinessException ex, HttpServletResponse response) throws IOException {
      response.setStatus(ex.getStatus());            // 401/404/409 等原状态码
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(objectMapper.writeValueAsString(
          Map.of("code", ex.getCode(), "message", ex.getMessage())));
  }
  ```
  用 `response.getWriter()` 直接输出绕过 MessageConverter 的内容协商，任何 Accept 头都能拿到 JSON。
- 返回体结构必须与现有 JSON 错误体**完全一致**（code/message 字段名、错误码值都不变），前端 axios 拦截器已经按这个格式解析，不能破坏普通 API 的现有行为
- 确认所有 handler 都改到位后，普通请求（Accept: application/json）行为与现在一字不差

### B. 测试

- JUnit：mock 一个 BusinessException 场景，验证响应是 401/对应状态码 + JSON body 含正确 code（用 MockMvc 或直接调用 handler 方法断言写入 response 的内容）
- **关键验收（手动 curl 实测）**：
  ```bash
  # 无 token（之前是空 500）
  curl -i -H "Accept: text/event-stream" http://127.0.0.1:8080/api/executions/1/stream
  # 期望：HTTP/1.1 401 + {"code":"UNAUTHORIZED","message":"缺少 Bearer token"}
  # 坏 token（之前也是空 500）
  curl -i -H "Accept: text/event-stream" -H "Authorization: Bearer bad" http://127.0.0.1:8080/api/executions/1/stream
  # 期望：HTTP/1.1 401 + TOKEN_EXPIRED 或 UNAUTHORIZED（按你们现有判定）
  ```
- 回归：跑一遍契约脚本 `python docs/contract_check.py http://127.0.0.1:8080`，鉴权相关检查必须仍全绿

## 验收清单

- [ ] curl 实测：Accept: text/event-stream + 无 token / 坏 token → 401 + JSON（不再是空 500）
- [ ] 普通 API 错误响应行为不变（契约脚本鉴权 8 项全过）
- [ ] mvn test 全绿
- [ ] 禁区 git diff 为空（LlmNodeExecutor/HttpToolInvoker/engine 零改动）

## 上报规则

改了什么、curl 实测输出贴原文、契约脚本结果、任何需要裁决的问题——按老格式汇报。
