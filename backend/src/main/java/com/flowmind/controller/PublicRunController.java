package com.flowmind.controller;

import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.UserContext;
import com.flowmind.dto.publish.PublicRunRequest;
import com.flowmind.entity.Execution;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowApiKey;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.service.PublishService;
import com.flowmind.service.RateLimitService;
import com.flowmind.service.ExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部调用入口（阶梯 8）：API Key 鉴权（AuthInterceptor 已白名单 /api/public/run/**）。
 * 结果获取为轮询（调用方循环查 status），同步阻塞等待是二期。
 */
@RestController
@RequestMapping("/api/public/run")
@RequiredArgsConstructor
public class PublicRunController {

    private static final int API_RATE_LIMIT = 10;
    private static final int API_RATE_WINDOW_SECONDS = 60;

    private final PublishService publishService;
    private final ExecutionService executionService;
    private final RateLimitService rateLimitService;
    private final WorkflowMapper workflowMapper;
    private final UserMapper userMapper;
    private final ExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    @Value("${flowmind.public-path-prefix:}")
    private String publicPathPrefix;

    private String baseUrl(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto");
        String host = header(request, "X-Forwarded-Host");
        if (host == null) {
            host = header(request, "Host");
        }
        if (host == null) {
            return "http://localhost:8080";
        }
        return (proto != null ? proto : "http") + "://" + host;
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.split(",")[0].trim();
    }

    private String normalizedPrefix() {
        String prefix = publicPathPrefix == null ? "" : publicPathPrefix.trim();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /**
     * 浏览器直接打开链接的友好说明（阶梯 9.5）：
     * 不校验 key、不计入限流——这是"浏览者"而非"调用者"。
     */
    @GetMapping("/{apiKey}")
    public Map<String, Object> guide(@PathVariable String apiKey, HttpServletRequest request) {
        String url = baseUrl(request) + normalizedPrefix() + "/api/public/run/<你的apiKey>";
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("curl",
                "curl -X POST '" + url + "' -H 'Content-Type: application/json' -d '{\"input\": {\"city\": \"南昌\"}}'");
        example.put("note", "请求体为工作流输入节点的参数字段（包在 input 对象里）；限流 10 次/分钟");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "GET_NOT_SUPPORTED");
        body.put("message", "此链接是工作流的 HTTP API 端点，需要在代码中通过 POST 请求调用，不能直接在浏览器打开。");
        body.put("example", example);
        return body;
    }

    @PostMapping("/{apiKey}")
    public ResponseEntity<Map<String, Long>> run(@PathVariable String apiKey,
                                                 @Valid @RequestBody PublicRunRequest req) {
        WorkflowApiKey key = requireValidKey(apiKey);
        if (!rateLimitService.tryAcquire("rl:api:" + apiKey,
                API_RATE_LIMIT, API_RATE_WINDOW_SECONDS)) {
            throw new BusinessException(ErrorCode.API_RATE_LIMITED);
        }
        Workflow workflow = workflowMapper.selectById(key.getWorkflowId());
        if (workflow == null) {
            // 工作流已删除但 key 残留：等同 key 失效
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        User owner = userMapper.selectById(workflow.getUserId());
        UserContext.set(workflow.getUserId(), owner == null ? "user" : owner.getRole());
        try {
            Long executionId;
            try {
                executionId = executionService.submit(workflow.getId(), req.getInput(), "api");
            } catch (BusinessException e) {
                // 入参错误文案友好化（阶梯 9.5）：指明字段名与请求体示例
                if (ErrorCode.INVALID_INPUT == e.getErrorCode()) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, friendlyInputError(e.getMessage()));
                }
                throw e;
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("executionId", executionId));
        } finally {
            UserContext.clear();
        }
    }

    /** "缺少必填字段: city" → 含字段名、来源说明与请求体示例的友好文案。 */
    private String friendlyInputError(String original) {
        String field = original != null && original.contains(": ")
                ? original.substring(original.indexOf(": ") + 2).trim() : "input";
        return "输入参数有误：缺少字段 '" + field + "'（工作流输入节点定义），"
                + "请求体示例 {\"input\": {\"" + field + "\": \"值\"}}";
    }

    @GetMapping("/{apiKey}/executions/{executionId}")
    public Map<String, Object> result(@PathVariable String apiKey, @PathVariable Long executionId) {
        WorkflowApiKey key = requireValidKey(apiKey);
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null || !execution.getWorkflowId().equals(key.getWorkflowId())) {
            // 含横向越权防护：key A 查 key B 的执行一律 404
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", execution.getStatus());
        body.put("totalTokens", execution.getTotalTokens());
        body.put("errorMessage", execution.getErrorMessage());
        if ("success".equals(execution.getStatus())) {
            body.put("output", parseJson(execution.getOutput()));
        }
        return body;
    }

    private WorkflowApiKey requireValidKey(String apiKey) {
        WorkflowApiKey key = publishService.findByKey(apiKey);
        if (key == null) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        return key;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
