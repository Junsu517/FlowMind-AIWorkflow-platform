package com.flowmind.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收尾修复回归：异常处理器直接写响应，绕过内容协商。
 * 直接调用 handler 断言写入内容（SSE 的 Accept: text/event-stream 场景由
 * GlobalExceptionHandlerSseAcceptTest 端到端验证，此处的 Mock 响应不感知 Accept）。
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new ObjectMapper());
        response = new MockHttpServletResponse();
    }

    @Test
    void businessExceptionWritesStatusAndJson() throws Exception {
        handler.handleBusiness(
                new BusinessException(ErrorCode.UNAUTHORIZED, "缺少 Bearer token"), response);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"缺少 Bearer token\"}",
                response.getContentAsString());
    }

    @Test
    void businessExceptionUsesErrorCodeDefaultMessage() throws Exception {
        handler.handleBusiness(new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND), response);

        assertEquals(404, response.getStatus());
        assertEquals("{\"code\":\"WORKFLOW_NOT_FOUND\",\"message\":\"工作流不存在\"}",
                response.getContentAsString());
    }

    @Test
    void invalidGraphKeepsSpecificMessage() throws Exception {
        handler.handleBusiness(
                new BusinessException(ErrorCode.INVALID_GRAPH, "图中存在环"), response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_GRAPH"));
    }

    @Test
    void unreadableBodyMapsToValidationError() throws Exception {
        handler.handleUnreadable(
                new org.springframework.web.bind.MissingServletRequestParameterException("page", "int"),
                response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"VALIDATION_ERROR\""),
                response.getContentAsString());
    }

    @Test
    void unknownExceptionMapsTo500InternalError() throws Exception {
        handler.handleUnknown(new RuntimeException("boom"), response);

        assertEquals(500, response.getStatus());
        assertEquals("{\"code\":\"INTERNAL_ERROR\",\"message\":\"服务器内部错误\"}",
                response.getContentAsString());
    }
}
