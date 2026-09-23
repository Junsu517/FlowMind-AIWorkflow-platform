package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.JwtUtil;
import com.flowmind.common.UserContext;
import com.flowmind.dto.auth.AuthResponse;
import com.flowmind.dto.auth.LoginRequest;
import com.flowmind.dto.auth.RegisterRequest;
import com.flowmind.dto.auth.UserVO;
import com.flowmind.entity.User;
import com.flowmind.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 只用 BCrypt 编码器，不引入 Spring Security 过滤器链 */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final DemoWorkflowService demoWorkflowService;

    public AuthResponse register(RegisterRequest req) {
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已被注册: " + req.getUsername());
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(PASSWORD_ENCODER.encode(req.getPassword()));
        user.setEmail(req.getEmail() == null ? "" : req.getEmail());
        userMapper.insert(user);
        // 注册即送示例工作流；创建失败不影响注册本身
        try {
            demoWorkflowService.createDemoWorkflowFor(user.getId());
        } catch (Exception e) {
            log.warn("示例工作流创建失败（注册不受影响）: userId={}, {}", user.getId(), e.getMessage());
        }
        // role/status/createdAt 由数据库默认值生成，回读一次
        User dbUser = userMapper.selectById(user.getId());
        return new AuthResponse(jwtUtil.generate(dbUser.getId(), dbUser.getRole()), toVO(dbUser));
    }

    public AuthResponse login(LoginRequest req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null || !PASSWORD_ENCODER.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        return new AuthResponse(jwtUtil.generate(user.getId(), user.getRole()), toVO(user));
    }

    public UserVO me() {
        User user = userMapper.selectById(UserContext.getUserId());
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        return toVO(user);
    }

    private UserVO toVO(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
