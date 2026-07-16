package com.xianyusmart.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 商品配置实体类
 */
@Data
public class XianyuGoodsConfig {

    public static final String DEFAULT_AUTO_RATE_CONTENT = "交易愉快，感谢支持，期待再次合作。满意的话欢迎点亮小红花。";
    
    /**
     * 主键ID
     */
    private Long id;

    @JsonIgnore
    private Long tenantId;
    
    /**
     * 闲鱼账号ID
     */
    private Long xianyuAccountId;
    
    /**
     * 本地闲鱼商品ID
     */
    private Long xianyuGoodsId;
    
    /**
     * 闲鱼的商品ID
     */
    private String xyGoodsId;
    
    /**
     * 自动发货开关：1-开启，0-关闭，默认关闭
     */
    private Integer xianyuAutoDeliveryOn = 0;
    
    /**
     * 自动回复开关：1-开启，0-关闭，默认关闭
     */
    private Integer xianyuAutoReplyOn = 0;

    /**
     * 自动评价开关：1-开启，0-关闭，默认关闭
     */
    private Integer xianyuAutoRateOn = 0;

    /**
     * 自动评价文案
     */
    private String xianyuAutoRateContent = DEFAULT_AUTO_RATE_CONTENT;

    /**
     * 自动擦亮开关：1-开启，0-关闭，默认关闭
     */
    private Integer xianyuAutoPolishOn = 0;

    /**
     * 最近成功擦亮时间，用于控制同一商品每天只执行一次
     */
    private Long lastPolishTime;
    
    /**
     * 携带上下文开关：1-开启，0-关闭，默认开启，跟随自动回复开关
     */
    private Integer xianyuAutoReplyContextOn = 1;
    
    private Integer xianyuKeywordReplyOn = 0;

    /**
     * 人工干预开关：1-开启，0-关闭，默认关闭
     * 开启后延时任务到期时若卖家已人工回复则取消自动回复
     */
    private Integer humanInterventionOn = 0;

    private Integer humanInterventionMinutes = 10;
    
    /**
     * 固定资料（用于AI自动回复）
     */
    private String fixedMaterial;
    
    /**
     * 创建时间
     */
    private String createTime;
    
    /**
     * 更新时间
     */
    private String updateTime;
}
