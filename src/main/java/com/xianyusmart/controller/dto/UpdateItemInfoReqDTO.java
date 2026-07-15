package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 更新本地商品资料请求
 */
@Data
public class UpdateItemInfoReqDTO {

    private Long xianyuAccountId;

    private String xyGoodsId;

    private String title;

    private String soldPrice;

    private String detailInfo;

    private String coverPic;
}
