package com.flowmind.controller;

import com.flowmind.dto.publish.PublishVO;
import com.flowmind.service.PublishService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布管理（JWT 保护）。runUrl 的 host 取法（上报项）：
 * 优先 X-Forwarded-Proto/X-Forwarded-Host（nginx 反代），其次请求 Host 头，兜底 localhost:8080。
 * 路径前缀 flowmind.public-path-prefix：生产 nginx 挂 /fm/ 下时 env 设 PUBLIC_PATH_PREFIX=/fm。
 */
@RestController
@RequestMapping("/api/workflows/{id}/publish")
@RequiredArgsConstructor
public class PublishController {

    private final PublishService publishService;

    @org.springframework.beans.factory.annotation.Value("${flowmind.public-path-prefix:}")
    private String publicPathPrefix;

    @PostMapping
    public ResponseEntity<PublishVO> publish(@PathVariable Long id, HttpServletRequest request) {
        PublishVO vo = publishService.publish(id);
        vo.setRunUrl(buildRunUrl(baseUrl(request), publicPathPrefix,
                vo.getApiKey() != null ? vo.getApiKey() : PublishService.KEY_PLACEHOLDER));
        return ResponseEntity.status(vo.getApiKey() != null ? HttpStatus.CREATED : HttpStatus.OK).body(vo);
    }

    @GetMapping
    public PublishVO get(@PathVariable Long id, HttpServletRequest request) {
        PublishVO vo = publishService.get(id);
        if (vo.isPublished()) {
            vo.setRunUrl(buildRunUrl(baseUrl(request), publicPathPrefix, PublishService.KEY_PLACEHOLDER));
        }
        return vo;
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        publishService.revoke(id);
        return ResponseEntity.noContent().build();
    }

    /** host + 部署前缀 + /api/public/run/{key}；prefix 为空即本地直连行为。
     *  容忍 "fm"/"/fm"/"/fm/" 三种写法（生产 env 用 /fm）。 */
    static String buildRunUrl(String baseUrl, String prefix, String apiKey) {
        String normalizedPrefix = prefix == null ? "" : prefix.trim();
        if (normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        if (!normalizedPrefix.isEmpty() && !normalizedPrefix.startsWith("/")) {
            normalizedPrefix = "/" + normalizedPrefix;
        }
        return baseUrl + normalizedPrefix + "/api/public/run/" + apiKey;
    }

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
}
