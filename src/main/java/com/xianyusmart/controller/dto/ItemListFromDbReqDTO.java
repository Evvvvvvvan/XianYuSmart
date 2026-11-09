package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 从数据库获取商品列表请求DTO
 */
@Data
public class ItemListFromDbReqDTO {
    
    /**
     * 只显示在售商品
     * true=只显示在售(status=0)，false/null=显示全部（默认）
     */
    private Boolean onlyOnSale = false;

    /**
     * 精确筛选商品状态（可选，优先级高于 onlyOnSale）
     * 0=在售 1=已下架 2=已售出 -1=已删除 -9=平台失效
     */
    private Integer status;
    
    /**
     * 闲鱼账号ID（可选）
     */
    private Long xianyuAccountId;
    
    /**
     * 页码，从1开始
     * 默认1
     */
    private Integer pageNum = 1;
    
    /**
     * 每页数量
     * 默认20
     */
    private Integer pageSize = 20;
}
