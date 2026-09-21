package com.flowmind.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String email;
    private String role;
    private LocalDateTime createdAt;
}
