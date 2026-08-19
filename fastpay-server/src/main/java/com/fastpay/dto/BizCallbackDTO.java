package com.fastpay.dto;

import com.fastpay.entity.Merchant;
import com.fastpay.entity.MerchantChannel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author xiaomo37564459
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class BizCallbackDTO {

    private PayNotifyDTO payNotifyDTO;

    private Merchant merchant;

    private MerchantChannel merchantChannel;

}
