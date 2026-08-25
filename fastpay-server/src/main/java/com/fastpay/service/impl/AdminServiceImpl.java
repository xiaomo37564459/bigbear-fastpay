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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 留空是故意的：生产 yml 里不配这项，initDefaultAdmin() 会随机生成一个 16 位密码并
     * 写到 initialPasswordFile 指定的受限文件里（权限 0600），日志里只留出口路径，不出现密码本身。
     * 避免仓库/配置文件里出现任何默认密码。dev/测试用的固定密码写在 application-dev.yml 里。
     */
    @Value("${fastpay.admin.password:}")
    private String defaultPassword;

    /**
     * 首次部署自动生成的初始管理员密码写到哪。
     * 默认写到 JVM 工作目录下 fastpay-initial-admin-password.txt。生产走 systemd 的
     * WorkingDirectory=/opt/fastpay，所以最终文件是 /opt/fastpay/fastpay-initial-admin-password.txt，
     * 权限 rw-------，只有 fastpay 账号（或 root）能读到。
     *
     * MTM-246：修复前这里的实现是把明文密码 warn 到日志里，而线上日志默认全局可读，
     * 相当于把首任管理员账号密码送给了任何能登上机器的人。
     */
    @Value("${fastpay.admin.initial-password-file:fastpay-initial-admin-password.txt}")
    private String initialPasswordFile;

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
            // 没配置初始密码 —— 生产环境走这里：随机生成一串，写进受限文件（0600）
            passwordPlain = RandomUtil.randomString("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789", 16);
            generated = true;
        } else {
            passwordPlain = defaultPassword;
        }

        // 顺序很关键（MTM-246）：随机分支下先把密码文件落到磁盘，再往库里写管理员记录。
        // 反过来的话，一旦文件写失败、异常抛出，下次启动库里已经有管理员了、走不进这段，
        // 运维就永远拿不到密码，只能删记录重建，非常麻烦。
        Path passwordFile = null;
        if (generated) {
            passwordFile = writeInitialPasswordFile(username, passwordPlain);
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
            // MTM-246：日志里绝对不许出现密码明文，只留一条「去哪拿」。
            // 线上日志文件默认全局可读，一旦密码进日志，任何能登上机器的账号都能翻出来。
            log.warn("========================================================");
            log.warn("首次启动，已自动创建管理员账号：{}", username);
            log.warn("初始密码已写入受限文件（权限 rw-------，只有服务账号能读）：");
            log.warn("  {}", passwordFile);
            log.warn("请以服务账号（通常是 fastpay，或 root）读取该文件，登录后台后立即修改密码，");
            log.warn("修改入口：右上角头像 -> 账号设置。改完之后手动删除该文件：");
            log.warn("  rm {}", passwordFile);
            log.warn("========================================================");
        } else {
            log.info("已初始化管理员账号: {}", username);
        }
    }

    /**
     * 把首次部署生成的初始管理员密码落到一份 0600 权限的文件里。
     *
     * MTM-246：修复前 initDefaultAdmin() 把明文密码 warn 到日志里，而线上日志文件默认全局可读，
     * 相当于把首任管理员账号密码送给了任何能登上机器的人。这里改成写到只有属主可读写的文件里，
     * 日志里只留出口路径。首次登录改完密码后运维应手动删掉这份文件（日志里已经贴了 rm 命令）。
     *
     * 实现细节：
     *   · POSIX 文件系统（生产 Linux、CI Linux/Mac）：在 open 时就把权限设成 rw-------，
     *     避免"先建 644 再 chmod"这段窗口期里被别的进程读到。
     *   · 非 POSIX 文件系统（Windows 本地开发）：退到 File.setReadable/Writable 做兜底，
     *     只让当前用户可读写；这个平台不跑生产。
     *   · 写失败 → 抛 BusinessException，让调用方看到、启动直接失败：
     *     宁可服务起不来，也不能起来了但没人拿得到初始密码。
     */
    private Path writeInitialPasswordFile(String username, String passwordPlain) {
        Path target = Paths.get(initialPasswordFile).toAbsolutePath().normalize();
        Path tmp = null;
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String body = "# 首次部署自动生成的初始管理员账号（MTM-246）\n"
                    + "# 用这一对登进后台后请立即修改密码，改完手动删除本文件：\n"
                    + "#   rm " + target + "\n"
                    + "username=" + username + "\n"
                    + "password=" + passwordPlain + "\n";

            // 先写到同目录的临时文件再原子移过去，避免读者读到"半份"或旧内容
            tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.deleteIfExists(tmp);

            boolean posix = tryCreatePosixFile(tmp);
            if (!posix) {
                Files.createFile(tmp);
            }
            Files.writeString(tmp, body, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            if (!posix) {
                // Windows 兜底：文件系统不支持 POSIX，靠 File API 收权限
                java.io.File f = tmp.toFile();
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(false, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(false, false);
                //noinspection ResultOfMethodCallIgnored
                f.setExecutable(false, false);
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, true);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, true);
            }

            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // move 成功之后 tmp 已经不存在，finally 里就不用再去删
            tmp = null;

            // move 之后再核一次权限，防止 REPLACE_EXISTING 时继承了旧文件的权限
            try {
                Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(target, ownerOnly);
            } catch (UnsupportedOperationException windowsHasNoPosix) {
                // 与上面 Windows 兜底同理
            }

            return target;
        } catch (IOException e) {
            throw new BusinessException("初始管理员密码文件写入失败：" + target + " —— " + e.getMessage());
        } finally {
            // 任何一步失败都把留下的 .tmp 中间产物清掉，别在磁盘上累计
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清不掉就算了，别再抛第二次异常盖掉原始失败原因
                }
            }
        }
    }

    /**
     * 尝试用 POSIX 0600 属性创建文件。返回 false 表示当前文件系统不支持 POSIX（Windows）。
     */
    private boolean tryCreatePosixFile(Path path) throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(ownerOnly);
            Files.createFile(path, attr);
            return true;
        } catch (UnsupportedOperationException windowsHasNoPosix) {
            return false;
        } catch (FileAlreadyExistsException e) {
            // 上一步 deleteIfExists 之后又有别的进程建了同名文件（极端情况），当作 POSIX 失败往下走
            return false;
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
