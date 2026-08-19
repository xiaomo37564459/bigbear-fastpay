package com.fastpay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fastpay.dto.LoginDTO;
import com.fastpay.dto.UpdatePasswordDTO;
import com.fastpay.dto.UpdateUsernameDTO;
import com.fastpay.entity.Admin;
import com.fastpay.vo.AdminProfileVO;
import com.fastpay.vo.DashboardVO;
import com.fastpay.vo.LoginVO;
import com.fastpay.vo.UpdateCredentialVO;

/**
 * 管理员服务接口
 *
 * @author FastPay
 */
public interface AdminService extends IService<Admin> {

    /**
     * 管理员登录
     *
     * @param dto 登录信息
     * @param ip  客户端IP
     * @return 登录结果
     */
    LoginVO login(LoginDTO dto, String ip);

    /**
     * 获取控制台统计数据
     *
     * @return 统计数据
     */
    DashboardVO getDashboard();

    /**
     * 初始化默认管理员账号
     */
    void initDefaultAdmin();

    /**
     * 查询当前登录管理员的资料（不含密码）
     *
     * @param adminId 管理员ID
     * @return 管理员资料
     */
    AdminProfileVO getProfile(Long adminId);

    /**
     * 修改当前管理员的登录账号。
     * 需要用当前密码二次确认；修改成功后老 token 立即失效，返回新 token 供前端替换本地缓存。
     *
     * @param adminId 管理员ID
     * @param dto     新账号 + 当前密码
     * @return 新的账号和令牌
     */
    UpdateCredentialVO updateUsername(Long adminId, UpdateUsernameDTO dto);

    /**
     * 修改当前管理员的密码。
     * 需要用旧密码二次确认；新密码要通过 PasswordPolicy 强度校验、且和 confirmPassword 一致。
     * 修改成功后老 token 立即失效，返回新 token 供前端替换本地缓存（本次前端交付会主动清 token 并跳登录页）。
     *
     * @param adminId 管理员ID
     * @param dto     旧密码 + 新密码 + 确认密码
     * @return 新的账号和令牌
     */
    UpdateCredentialVO updatePassword(Long adminId, UpdatePasswordDTO dto);
}
