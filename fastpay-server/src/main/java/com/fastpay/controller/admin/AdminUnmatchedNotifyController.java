package com.fastpay.controller.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fastpay.common.PageResult;
import com.fastpay.common.Result;
import com.fastpay.entity.UnmatchedNotify;
import com.fastpay.service.UnmatchedNotifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理后台 - 未匹配收款通知
 * 收到付款通知但按 (商户 + 支付方式 + pay_amount) 找不到对应待支付订单时，会落到 fp_unmatched_notify 表。
 * 这里给运营看列表，方便配合 /api/admin/order/{orderNo}/confirm 手动把钱认到对的订单上。
 */
@Tag(name = "管理后台-未匹配收款通知", description = "钱到了但认不到订单的记录，用于人工兜底")
@RestController
@RequestMapping("/api/admin/unmatched-notify")
public class AdminUnmatchedNotifyController {

    private final UnmatchedNotifyService service;

    public AdminUnmatchedNotifyController(UnmatchedNotifyService service) {
        this.service = service;
    }

    /**
     * 分页查询
     */
    @Operation(summary = "分页查询未匹配通知", description = "handleStatus: 0-待处理 1-已处理 2-已忽略；不传即不限")
    @GetMapping("/page")
    public Result<PageResult<UnmatchedNotify>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer handleStatus,
            @RequestParam(required = false) Long merchantId) {
        Page<UnmatchedNotify> page = service.pageUnmatched(new Page<>(current, size), handleStatus, merchantId);
        PageResult<UnmatchedNotify> result = new PageResult<>(
                page.getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
        return Result.success(result);
    }

    /**
     * 人工把某条未匹配通知标记为"已处理"（对应到了某个订单）
     * body: { "remark": "对应到订单 xxx", "handledOrderNo": "FP..." }
     */
    @Operation(summary = "人工处理未匹配通知")
    @PostMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.handleUnmatched(id, body.get("remark"), body.get("handledOrderNo"));
        return Result.success("已标记为已处理", null);
    }

    /**
     * 人工忽略某条未匹配通知（比如是重复通知、或者是其他账户流水）
     * body: { "remark": "重复通知" }
     */
    @Operation(summary = "忽略未匹配通知")
    @PostMapping("/{id}/ignore")
    public Result<Void> ignore(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.ignoreUnmatched(id, body.get("remark"));
        return Result.success("已忽略", null);
    }
}
