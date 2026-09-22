package com.flowmind.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^\\w+$", message = "用户名仅支持字母、数字、下划线")
    @Size(min = 3, max = 20, message = "用户名长度须在 3~20 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度须在 6~64 之间")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱过长")
    private String email;
}
