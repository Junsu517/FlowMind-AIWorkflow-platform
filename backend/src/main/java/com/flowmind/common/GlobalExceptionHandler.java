package com.flowmind.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;

/**
 * 所有 handler 直接写 HttpServletResponse，绕过 MessageConverter 内容协商：
 * SSE 订阅请求（Accept: text/event-stream）鉴权失败时，返回 JSON 实体会触发
 * HttpMediaTypeNotAcceptableException 使异常处理器自身崩溃（空 500），
 * 直接输出可保证任何 Accept 头都拿到结构一致的 JSON 错误体（code/message）。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(BusinessException.class)
    public void handleBusiness(BusinessException ex, HttpServletResponse response) throws IOException {
        writeJson(response, ex.getErrorCode().getStatus(),
                new ErrorResponse(ex.getErrorCode().getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidation(MethodArgumentNotValidException ex, HttpServletResponse response)
            throws IOException {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("参数不合法");
        writeJson(response, ErrorCode.VALIDATION_ERROR.getStatus(),
                new ErrorResponse(ErrorCode.VALIDATION_ERROR.getCode(), message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public void handleUnreadable(Exception ex, HttpServletResponse response) throws IOException {
        writeJson(response, ErrorCode.VALIDATION_ERROR.getStatus(),
                new ErrorResponse(ErrorCode.VALIDATION_ERROR.getCode(), "请求参数格式错误: " + ex.getMessage()));
    }

    /** 405 而非兜底 500（阶梯 9.5：浏览器打开 API 链接一类场景）。 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public void handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException ex,
                                         HttpServletResponse response) throws IOException {
        java.util.List<String> allowed = ex.getSupportedHttpMethods() == null
                ? java.util.List.of("POST")
                : ex.getSupportedHttpMethods().stream().map(org.springframework.http.HttpMethod::name).toList();
        com.fasterxml.jackson.databind.node.ArrayNode allowedArray = objectMapper.createArrayNode();
        allowed.forEach(allowedArray::add);
        String body = objectMapper.createObjectNode()
                .put("code", "METHOD_NOT_ALLOWED")
                .put("message", "此端点仅支持 " + String.join(", ", allowed) + " 请求")
                .set("allowedMethods", allowedArray)
                .toString();
        response.setStatus(405);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    @ExceptionHandler(Exception.class)
    public void handleUnknown(Exception ex, HttpServletResponse response) throws IOException {
        log.error("未处理异常", ex);
        writeJson(response, ErrorCode.INTERNAL_ERROR.getStatus(),
                new ErrorResponse(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }

    private void writeJson(HttpServletResponse response, int status, ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
