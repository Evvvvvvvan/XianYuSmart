package com.xianyusmart.controller.dto;

import com.xianyusmart.entity.XianyuGoodsInfo;
import lombok.Data;

@Data
public class ItemWithConfigDTO {
    
    private XianyuGoodsInfo item;
    
    private Integer xianyuAutoDeliveryOn;
    
    private Integer xianyuAutoReplyOn;

    /**
     * 自动评价开关
     */
    private Integer xianyuAutoRateOn;

    private String xianyuAutoRateContent;

    /**
     * 自动擦亮开关
     */
    private Integer xianyuAutoPolishOn;
    
    private Integer xianyuAutoReplyContextOn;
    
    private Integer xianyuKeywordReplyOn;

    private Integer humanInterventionOn;

    private Integer humanInterventionMinutes;
    
    private Integer autoDeliveryType;
    
    private String autoDeliveryContent;
}
