package com.fastpay.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastpay.common.BusinessException;
import com.fastpay.common.Constants;
import com.fastpay.dto.LoginDTO;
import com.fastpay.dto.UpdatePasswordDTO;
import com.fastpay.dto.UpdateUsernameDTO;
import com.fastpay.entity.Admin;
import com.fastpay.entity.PayOrder;
import com.fastpay.mapper.*;
import com.fastpay.service.AdminService;
import com.fastpay.util.JwtUtil;
import com.fastpay.util.PasswordHasher;
import com.fastpay.util.PasswordPolicy;
import com.fastpay.vo.AdminProfileVO;
import com.fastpay.vo.DashboardVO;
import com.fastpay.vo.LoginVO;
import com.fastpay.vo.UpdateCredentialVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理员服务实现类
 *
 * @author xiaomo37564459
 */
@Slf4j
@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements AdminService {

    private final JwtUtil jwtUtil;
    private final MerchantMapper merchantMapper;
    private final ShopMapper shopMapper;
    private final PayQrcodeMapper payQrcodeMapper;
    private final PayOrderMapper payOrderMapper;

    /**
     * 初始化管理员用户名。生产环境走环境变量（application-prod.yml 里读 FASTPAY_ADMIN_INITIAL_USERNAME）。
     */
    @Value("${fastpay.admin.username:admin}")
    private String defaultUsername;

    /**
     * 初始化管理员密码。
     * 留空是故意的：生产 yml 里不配这项，InitConfig 会随机生成一个 16 位密码并打到 warn 日志里，
     * 避免仓库/配置文件里出现任何默认密码。dev/测试用的固定密码写在 application-dev.yml 里。
     */
    @Value("${fastpay.admin.password:}")
    private String defaultPassword;

    public AdminServiceImpl(JwtUtil jwtUtil, MerchantMapper merchantMapper,
                           ShopMapper shopMapper, PayQrcodeMapper payQrcodeMapper,
                           PayOrderMapper payOrderMapper) {
        this.jwtUtil = jwtUtil;
        this.merchantMapper = merchantMapper;
        this.shopMapper = shopMapper;
        this.payQrcodeMapper = payQrcodeMapper;
        this.payOrderMapper = payOrderMapper;
    }

    @Override
    public LoginVO login(LoginDTO dto, String ip) {
        // 查询管理员
        Admin admin = this.getOne(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, dto.getUsername()));

        if (admin == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 验证密码：兼容库里遗留的老格式（无盐 MD5）和新格式（bcrypt）
        if (!PasswordHasher.matches(dto.getPassword(), admin.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 检查状态
        if (!Constants.Status.ENABLED.equals(admin.getStatus())) {
            throw new BusinessException("账号已被禁用");
        }

        // 老格式账号顺手升级成 bcrypt：用户完全无感，一次登录后这条记录就再也不是 MD5 了
        if (PasswordHasher.isLegacy(admin.getPassword())) {
            admin.setPassword(PasswordHasher.hash(dto.getPassword()));
            log.info("管理员 {} 的密码已从老格式升级为 bcrypt", admin.getUsername());
        }

        // 更新登录信息
        admin.setLastLoginTime(LocalDateTime.now());
        admin.setLastLoginIp(ip);
        this.updateById(admin);

        // 生成Token（带 tokenVersion，让改密码之后旧 token 可以被拦截）
        int tokenVersion = admin.getTokenVersion() == null ? 0 : admin.getTokenVersion();
        String token = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "admin", tokenVersion);

        // 构建返回结果
        LoginVO vo = new LoginVO();
        vo.setUserId(admin.getId());
        vo.setUsername(admin.getUsername());
        vo.setNickname(admin.getNickname());
        vo.setAvatar(admin.getAvatar());
        vo.setUserType("admin");
        vo.setToken(token);

        return vo;
    }

    @Override
    public DashboardVO getDashboard() {
        DashboardVO vo = new DashboardVO();

        // 今日统计
        vo.setTodayOrderCount(payOrderMapper.countTodayOrders());
        vo.setTodaySuccessCount(payOrderMapper.countTodaySuccessOrders());
        vo.setTodayAmount(payOrderMapper.sumTodayAmount());

        // 总计统计
        vo.setTotalOrderCount(payOrderMapper.selectCount(null));
        vo.setTotalSuccessCount(payOrderMapper.selectCount(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, Constants.OrderStatus.PAID)));

        // 计算总交易金额
        LambdaQueryWrapper<PayOrder> amountQuery = new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, Constants.OrderStatus.PAID);
        BigDecimal totalAmount = payOrderMapper.selectList(amountQuery).stream()
                .map(PayOrder::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setTotalAmount(totalAmount);

        // 商户、店铺、二维码数量
        vo.setMerchantCount(merchantMapper.selectCount(null));
        vo.setShopCount(shopMapper.selectCount(null));
        vo.setQrcodeCount(payQrcodeMapper.selectCount(null));

        // 支付方式统计（成功订单）
        LambdaQueryWrapper<PayOrder> wxpayQuery = new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, Constants.OrderStatus.PAID)
                .eq(PayOrder::getPayType, "wxpay");
        vo.setWxpayCount(payOrderMapper.selectCount(wxpayQuery));
        BigDecimal wxpayAmount = payOrderMapper.selectList(wxpayQuery).stream()
                .map(PayOrder::getPayAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setWxpayAmount(wxpayAmount);

        LambdaQueryWrapper<PayOrder> alipayQuery = new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, Constants.OrderStatus.PAID)
                .eq(PayOrder::getPayType, "alipay");
        vo.setAlipayCount(payOrderMapper.selectCount(alipayQuery));
        BigDecimal alipayAmount = payOrderMapper.selectList(alipayQuery).stream()
                .map(PayOrder::getPayAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setAlipayAmount(alipayAmount);

        // 最近7天统计
        // Mapper 里 SQL 别名用的是 stat_date（避开 PG 的 date 关键字），这里映射回 date，
        // 保证接口返回给前端的字段名不变
        List<Map<String, Object>> recentStats = payOrderMapper.statsByDate(7);
        recentStats.forEach(stat -> {
            if (stat.containsKey("stat_date")) {
                stat.put("date", stat.remove("stat_date"));
            }
        });
        vo.setRecentStats(recentStats);

        return vo;
    }

    @Override
    public void initDefaultAdmin() {
        // 已经存在管理员就不动
        Long count = this.count();
        if (count > 0) {
            return;
        }

        String username = (defaultUsername == null || defaultUsername.isBlank()) ? "admin" : defaultUsername.trim();
        String passwordPlain;
        boolean generated = false;
        if (defaultPassword == null || defaultPassword.isBlank()) {
            // 没配置初始密码 —— 生产环境走这里：随机生成、日志里印一次给运维
            passwordPlain = RandomUtil.randomString("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789", 16);
            generated = true;
        } else {
            passwordPlain = defaultPassword;
        }

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(PasswordHasher.hash(passwordPlain));
        admin.setNickname("超级管理员");
        admin.setAvatar("/static/avatar/admin.png");
        admin.setStatus(Constants.Status.ENABLED);
        admin.setTokenVersion(0);
        this.save(admin);

        if (generated) {
            // 密码只在日志里出现一次，运维拿到之后请立刻登进后台改成自己的口令
            log.warn("========================================================");
            log.warn("首次启动，已自动创建管理员账号：");
            log.warn("  账号：{}", username);
            log.warn("  初始密码：{}", passwordPlain);
            log.warn("这条日志只在首次启动时出现一次，请立即登录后台修改密码。");
            log.warn("修改入口：右上角头像 -> 账号设置");
            log.warn("========================================================");
        } else {
            log.info("已初始化管理员账号: {}", username);
        }
    }

    @Override
    public AdminProfileVO getProfile(Long adminId) {
        Admin admin = requireAdmin(adminId);
        AdminProfileVO vo = new AdminProfileVO();
        vo.setId(admin.getId());
        vo.setUsername(admin.getUsername());
        vo.setNickname(admin.getNickname());
        vo.setAvatar(admin.getAvatar());
        vo.setLastLoginTime(admin.getLastLoginTime());
        vo.setLastLoginIp(admin.getLastLoginIp());
        return vo;
    }

    @Override
    public UpdateCredentialVO updateUsername(Long adminId, UpdateUsernameDTO dto) {
        Admin admin = requireAdmin(adminId);

        // 先用当前密码验身份（同时兼容老 MD5 和新 bcrypt）
        if (!PasswordHasher.matches(dto.getCurrentPassword(), admin.getPassword())) {
            throw BusinessException.badRequest("当前密码不正确");
        }

        String newUsername = dto.getNewUsername() == null ? "" : dto.getNewUsername().trim();
        if (newUsername.isEmpty()) {
            throw BusinessException.badRequest("请输入新账号");
        }
        if (newUsername.length() > 100) {
            throw BusinessException.badRequest("账号长度不能超过 100 个字符");
        }
        if (newUsername.equals(admin.getUsername())) {
            throw BusinessException.badRequest("新账号与当前账号一致，无需修改");
        }
        // 判重（管理员表里另一条记录已经用了这个账号）
        Long conflict = this.count(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, newUsername)
                .ne(Admin::getId, adminId));
        if (conflict != null && conflict > 0) {
            throw BusinessException.badRequest("该账号已被占用");
        }

        int newVersion = (admin.getTokenVersion() == null ? 0 : admin.getTokenVersion()) + 1;
        admin.setUsername(newUsername);
        admin.setTokenVersion(newVersion);
        this.updateById(admin);

        String token = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "admin", newVersion);
        UpdateCredentialVO vo = new UpdateCredentialVO();
        vo.setUsername(admin.getUsername());
        vo.setToken(token);
        return vo;
    }

    @Override
    public UpdateCredentialVO updatePassword(Long adminId, UpdatePasswordDTO dto) {
        Admin admin = requireAdmin(adminId);

        // 先用旧密码验身份（同时兼容老 MD5 和新 bcrypt）
        if (!PasswordHasher.matches(dto.getOldPassword(), admin.getPassword())) {
            throw BusinessException.badRequest("旧密码不正确");
        }

        String newPassword = dto.getNewPassword();
        if (newPassword == null || !newPassword.equals(dto.getConfirmPassword())) {
            throw BusinessException.badRequest("两次输入的新密码不一致");
        }
        if (newPassword.equals(dto.getOldPassword())) {
            throw BusinessException.badRequest("新密码不能与旧密码相同");
        }
        // 强度规则统一走 PasswordPolicy，方便前端提示口径和后端拦截口径完全一致
        PasswordPolicy.validate(newPassword);

        int newVersion = (admin.getTokenVersion() == null ? 0 : admin.getTokenVersion()) + 1;
        admin.setPassword(PasswordHasher.hash(newPassword));
        admin.setTokenVersion(newVersion);
        this.updateById(admin);

        String token = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "admin", newVersion);
        UpdateCredentialVO vo = new UpdateCredentialVO();
        vo.setUsername(admin.getUsername());
        vo.setToken(token);
        return vo;
    }

    private Admin requireAdmin(Long adminId) {
        if (adminId == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        Admin admin = this.getById(adminId);
        if (admin == null) {
            throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
        }
        return admin;
    }
}
