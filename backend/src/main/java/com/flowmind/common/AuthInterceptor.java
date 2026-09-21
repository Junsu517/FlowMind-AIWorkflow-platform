package com.flowmind.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 解析 Authorization: Bearer xxx，把 userId/role 放入 UserContext。
 * 白名单（register/login）在 WebMvcConfig 中排除。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少 Bearer token");
        }
        try {
            Claims claims = jwtUtil.parse(auth.substring(7));
            Object userId = claims.get("userId");
            if (userId instanceof Number number) {
                UserContext.set(number.longValue(), claims.get("role", String.class));
                return true;
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 中缺少用户信息");
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token 无效或已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
