package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单双方评价详情
 */
@Data
public class OrderRateDetailDTO {

    private String orderId;

    private String tradeStatus;

    private String sellerRateStatus;

    private String buyerRateStatus;

    private Boolean buyerRated = false;

    private Boolean synced = true;

    private Boolean canRate = false;

    private Boolean rated = false;

    private String statusText;

    private List<RateItemDTO> buyerRates = new ArrayList<>();

    private List<RateItemDTO> sellerRates = new ArrayList<>();

    @Data
    public static class RateItemDTO {

        private String content;

        private String createdTime;

        private Integer level;

        private Boolean main;

        private Boolean illegal;
    }
}
