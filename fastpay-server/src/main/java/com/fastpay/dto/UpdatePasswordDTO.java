package com.fastpay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改密码请求 DTO
 *
 * @author FastPay
 */
@Data
public class UpdatePasswordDTO {

    /**
     * 旧密码（必须先验证才能改）
     */
    @NotBlank(message = "请输入当前密码")
    private String oldPassword;

    /**
     * 新密码（强度规则由 PasswordPolicy 校验）
     */
    @NotBlank(message = "请输入新密码")
    private String newPassword;

    /**
     * 新密码确认（要与 newPassword 一致）
     */
    @NotBlank(message = "请再次输入新密码")
    private String confirmPassword;
}
