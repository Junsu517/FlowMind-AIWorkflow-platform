package com.flowmind.common;

import lombok.Getter;

/**
 * 错误码按 docs/API契约.md 定义；契约未定义的场景使用通用码，需与统筹确认后补充。
 */
@Getter
public enum ErrorCode {

    USERNAME_EXISTS(400, "USERNAME_EXISTS", "用户名已存在"),
    VALIDATION_ERROR(400, "VALIDATION_ERROR", "参数不合法"),
    INVALID_GRAPH(400, "INVALID_GRAPH", "工作流图不合法"),
    INVALID_INPUT(400, "INVALID_INPUT", "执行入参缺少必填字段"),

    BAD_CREDENTIALS(401, "BAD_CREDENTIALS", "用户名或密码错误"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "未登录或 token 无效"),

    FORBIDDEN(403, "FORBIDDEN", "无权访问该资源"),

    WORKFLOW_NOT_FOUND(404, "WORKFLOW_NOT_FOUND", "工作流不存在"),
    EXECUTION_NOT_FOUND(404, "EXECUTION_NOT_FOUND", "执行记录不存在"),
    KNOWLEDGE_BASE_NOT_FOUND(404, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"),

    DOCUMENT_TOO_LARGE(400, "DOCUMENT_TOO_LARGE", "文档超过 5MB 上限"),
    DOCUMENT_PARSE_ERROR(400, "DOCUMENT_PARSE_ERROR", "文档解析失败"),
    SCHEDULE_INVALID(400, "SCHEDULE_INVALID", "cron 表达式非法"),

    EMBEDDING_ERROR(502, "EMBEDDING_ERROR", "向量化服务不可用"),

    WORKFLOW_RUNNING(409, "WORKFLOW_RUNNING", "同一工作流已有执行在运行"),

    RATE_LIMITED(429, "RATE_LIMITED", "操作过于频繁，请稍后再试"),

    INTERNAL_ERROR(500, "INTERNAL_ERROR", "服务器内部错误");

    private final int status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(int status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
