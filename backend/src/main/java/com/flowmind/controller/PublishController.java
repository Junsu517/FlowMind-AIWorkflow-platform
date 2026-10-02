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
 */
@RestController
@RequestMapping("/api/workflows/{id}/publish")
@RequiredArgsConstructor
public class PublishController {

    private final PublishService publishService;

    @PostMapping
    public ResponseEntity<PublishVO> publish(@PathVariable Long id, HttpServletRequest request) {
        PublishVO vo = publishService.publish(id);
        String base = baseUrl(request);
        vo.setRunUrl(base + "/api/public/run/"
                + (vo.getApiKey() != null ? vo.getApiKey() : PublishService.KEY_PLACEHOLDER));
        return ResponseEntity.status(vo.getApiKey() != null ? HttpStatus.CREATED : HttpStatus.OK).body(vo);
    }

    @GetMapping
    public PublishVO get(@PathVariable Long id, HttpServletRequest request) {
        PublishVO vo = publishService.get(id);
        if (vo.isPublished()) {
            vo.setRunUrl(baseUrl(request) + "/api/public/run/" + PublishService.KEY_PLACEHOLDER);
        }
        return vo;
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        publishService.revoke(id);
        return ResponseEntity.noContent().build();
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
