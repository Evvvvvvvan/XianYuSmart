package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 固定内容发货模板
 */
@Data
@TableName("xianyu_fixed_delivery_template")
public class XianyuFixedDeliveryTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long xianyuAccountId;

    private String templateName;

    private String deliveryContent;

    private String messageTemplate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime = LocalDateTime.now();

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime = LocalDateTime.now();
}
