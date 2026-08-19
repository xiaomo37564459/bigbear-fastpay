package com.fastpay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改登录账号请求 DTO
 *
 * @author xiaomo37564459
 */
@Data
public class UpdateUsernameDTO {

    /**
     * 新的登录账号（普通字符串或邮箱格式均可，与登录接口保持一致）
     */
    @NotBlank(message = "请输入新账号")
    @Size(max = 100, message = "账号长度不能超过 100 个字符")
    private String newUsername;

    /**
     * 当前密码（改账号也必须先验证身份）
     */
    @NotBlank(message = "请输入当前密码")
    private String currentPassword;
}
