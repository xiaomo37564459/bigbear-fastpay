package com.fastpay.controller.admin;

import com.fastpay.common.Result;
import com.fastpay.dto.LoginDTO;
import com.fastpay.dto.UpdatePasswordDTO;
import com.fastpay.dto.UpdateUsernameDTO;
import com.fastpay.service.AdminService;
import com.fastpay.util.ClientIpResolver;
import com.fastpay.util.PasswordPolicy;
import com.fastpay.vo.AdminProfileVO;
import com.fastpay.vo.DashboardVO;
import com.fastpay.vo.LoginVO;
import com.fastpay.vo.UpdateCredentialVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理后台 - 认证控制器
 * 处理管理员登录、控制台数据、账号设置等
 *
 * @author xiaomo37564459
 */
@Tag(name = "管理后台-认证", description = "管理员登录、控制台数据、账号设置等接口")
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminService adminService;

    public AdminAuthController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 管理员登录
     *
     * @param dto     登录信息
     * @param request HTTP请求
     * @return 登录结果
     */
    @Operation(summary = "管理员登录", description = "管理员账号密码登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        String ip = ClientIpResolver.resolve(request);
        LoginVO vo = adminService.login(dto, ip);
        return Result.success("登录成功", vo);
    }

    /**
     * 获取控制台统计数据
     *
     * @return 统计数据
     */
    @Operation(summary = "控制台数据", description = "获取控制台统计数据")
    @GetMapping("/dashboard")
    public Result<DashboardVO> getDashboard() {
        DashboardVO vo = adminService.getDashboard();
        return Result.success(vo);
    }

    /**
     * 获取当前登录管理员资料（不含密码）
     */
    @Operation(summary = "当前管理员资料", description = "获取当前登录管理员的账号、昵称、最近登录记录等")
    @GetMapping("/profile")
    public Result<AdminProfileVO> getProfile(HttpServletRequest request) {
        Long adminId = (Long) request.getAttribute("userId");
        return Result.success(adminService.getProfile(adminId));
    }

    /**
     * 修改当前管理员登录账号
     */
    @Operation(summary = "修改登录账号", description = "修改当前管理员的登录账号，需当前密码验证；成功后返回新 token")
    @PutMapping("/profile/username")
    public Result<UpdateCredentialVO> updateUsername(@Valid @RequestBody UpdateUsernameDTO dto, HttpServletRequest request) {
        Long adminId = (Long) request.getAttribute("userId");
        UpdateCredentialVO vo = adminService.updateUsername(adminId, dto);
        return Result.success("账号已更新", vo);
    }

    /**
     * 修改当前管理员密码
     */
    @Operation(summary = "修改密码", description = "修改当前管理员密码，需旧密码验证；成功后老 token 立即失效")
    @PutMapping("/profile/password")
    public Result<UpdateCredentialVO> updatePassword(@Valid @RequestBody UpdatePasswordDTO dto, HttpServletRequest request) {
        Long adminId = (Long) request.getAttribute("userId");
        UpdateCredentialVO vo = adminService.updatePassword(adminId, dto);
        return Result.success("密码已更新，请使用新密码重新登录", vo);
    }

    /**
     * 返回密码强度规则，供前端提示（保证前后端提示口径一致）
     */
    @Operation(summary = "密码强度规则", description = "返回当前的密码强度规则文案与关键阈值")
    @GetMapping("/profile/password-policy")
    public Result<Map<String, Object>> getPasswordPolicy() {
        Map<String, Object> policy = new HashMap<>();
        policy.put("description", PasswordPolicy.DESCRIPTION);
        policy.put("minLength", PasswordPolicy.MIN_LENGTH);
        policy.put("maxLength", PasswordPolicy.MAX_LENGTH);
        return Result.success(policy);
    }

}
