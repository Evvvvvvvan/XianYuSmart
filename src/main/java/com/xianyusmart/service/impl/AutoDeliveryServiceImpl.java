package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.entity.XianyuGoodsAutoReplyRecord;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyusmart.service.AutoDeliveryService;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.EmailNotifyService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.OrderService;
import com.xianyusmart.service.WebSocketService;
import com.xianyusmart.service.delivery.DeliveryContext;
import com.xianyusmart.service.delivery.DeliveryStrategyResolver;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import com.xianyusmart.utils.HumanLikeDelayUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 自动发货服务实现类（编排层）
 *
 * <p>负责发货流程的编排，具体逻辑委托给 delivery 包下的组件：</p>
 * <ul>
 *   <li>{@link OrderDetailFetcher} - 订单详情获取与解析</li>
 *   <li>{@link DeliveryStrategyResolver} - 发货内容策略解析（文本/卡密/自定义）</li>
 * </ul>
 */
@Slf4j
@Service
public class AutoDeliveryServiceImpl implements AutoDeliveryService {
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    
    @Autowired
    private XianyuGoodsOrderMapper orderMapper;
    
    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    
    @Lazy
    @Autowired
    private WebSocketService webSocketService;
    
    @Autowired
    private com.xianyusmart.service.SentMessageSaveService sentMessageSaveService;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderDetailFetcher orderDetailFetcher;

    @Autowired
    private DeliveryStrategyResolver deliveryStrategyResolver;

    @Autowired
    private KamiConfigService kamiConfigService;

    @Autowired
    private BuyerMessageService buyerMessageService;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;
    
    @Override
    public XianyuGoodsConfig getGoodsConfig(Long accountId, String xyGoodsId) {
        return goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
    }
    
    @Override
    public XianyuGoodsAutoDeliveryConfig getAutoDeliveryConfig(Long accountId, String xyGoodsId) {
        return autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
    }
    
    @Override
    public void saveOrUpdateGoodsConfig(XianyuGoodsConfig config) {
        XianyuGoodsConfig existing = goodsConfigMapper.selectByAccountAndGoodsId(
                config.getXianyuAccountId(), config.getXyGoodsId());
        
        if (existing == null) {
            goodsConfigMapper.insert(config);
        } else {
            config.setId(existing.getId());
            goodsConfigMapper.update(config);
        }
    }
    
    @Override
    public void saveOrUpdateAutoDeliveryConfig(XianyuGoodsAutoDeliveryConfig config) {
        String skuId = config.getSkuId();
        if (skuId != null && skuId.isEmpty()) {
            skuId = null;
            config.setSkuId(null);
        }
        XianyuGoodsAutoDeliveryConfig existingConfig;
        if (skuId != null) {
            existingConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                    config.getXianyuAccountId(), config.getXyGoodsId(), skuId);
        } else {
            existingConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                    config.getXianyuAccountId(), config.getXyGoodsId());
        }
        
        if (existingConfig == null) {
            autoDeliveryConfigMapper.insert(config);
        } else {
            config.setId(existingConfig.getId());
            autoDeliveryConfigMapper.updateById(config);
        }
    }
    
    @Override
    public void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName, String content, Integer state) {
        recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, content, state, null, null);
    }
    
    public void recordAutoDelivery(Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName, 
                                   String content, Integer state, String pnmId, String orderId) {
        XianyuGoodsOrder record = new XianyuGoodsOrder();
        record.setXianyuAccountId(accountId);
        record.setXyGoodsId(xyGoodsId);
        record.setBuyerUserId(buyerUserId);
        record.setBuyerUserName(buyerUserName);
        record.setContent(content);
        record.setState(state);
        record.setPnmId(pnmId != null ? pnmId : "");
        record.setOrderId(orderId != null ? orderId : "");
        record.setConfirmState(0);
        
        orderMapper.insert(record);
    }
    
    @Override
    public void handleAutoDelivery(Long accountId, String xyGoodsId, String sId, String buyerUserId, String buyerUserName) {
        handleAutoDelivery(accountId, xyGoodsId, sId, buyerUserId, buyerUserName, null);
    }
    
    public void handleAutoDelivery(Long accountId, String xyGoodsId, String sId, String buyerUserId, String buyerUserName, String orderId) {
        try {
            log.info("【账号{}】处理自动发货: xyGoodsId={}, sId={}, buyerUserId={}, buyerUserName={}, orderId={}", 
                    accountId, xyGoodsId, sId, buyerUserId, buyerUserName, orderId);
            
            XianyuGoodsConfig goodsConfig = getGoodsConfig(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.info("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                return;
            }
            
            XianyuGoodsAutoDeliveryConfig deliveryConfig = getAutoDeliveryConfig(accountId, xyGoodsId);
            if (deliveryConfig == null || deliveryConfig.getAutoDeliveryContent() == null || 
                    deliveryConfig.getAutoDeliveryContent().isEmpty()) {
                log.warn("【账号{}】商品未配置自动发货内容: xyGoodsId={}", accountId, xyGoodsId);
                recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, null, 0, null, orderId);
                return;
            }
            
            String content = deliveryConfig.getAutoDeliveryContent();
            log.info("【账号{}】准备发送自动发货消息: content={}", accountId, content);

            HumanLikeDelayUtils.mediumDelay();
            HumanLikeDelayUtils.thinkingDelay();
            HumanLikeDelayUtils.typingDelay(content.length());
            
            String cid = sId.replace("@goofish", "");
            String toId = cid;
            
            boolean success = webSocketService.sendMessage(accountId, cid, toId, content);
            
            recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, content, success ? 1 : 0, null, orderId);
            
            if (success) {
                log.info("【账号{}】自动发货成功: xyGoodsId={}, buyerUserName={}, content={}", 
                        accountId, xyGoodsId, buyerUserName, content);
                sentMessageSaveService.saveAiAssistantReply(accountId, cid, toId, content, xyGoodsId);
            } else {
                log.error("【账号{}】自动发货失败: xyGoodsId={}", accountId, xyGoodsId);
            }
            
        } catch (Exception e) {
            log.error("【账号{}】自动发货异常: xyGoodsId={}", accountId, xyGoodsId, e);
            recordAutoDelivery(accountId, xyGoodsId, buyerUserId, buyerUserName, null, 0, null, orderId);
        }
    }
    
    @Override
    public void handleAutoReply(Long accountId, String xyGoodsId, String sId, String buyerMessage) {
        log.info("【账号{}】自动回复功能已移除: xyGoodsId={}", accountId, xyGoodsId);
    }
    
    private void recordAutoReply(Long accountId, String xyGoodsId, String buyerMessage, 
                                  String replyContent, String matchedKeyword, Integer state) {
        try {
            XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
            record.setXianyuAccountId(accountId);
            record.setXyGoodsId(xyGoodsId);
            record.setBuyerMessage(buyerMessage);
            record.setReplyContent(replyContent);
            record.setMatchedKeyword(matchedKeyword);
            record.setState(state);
            
            autoReplyRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("【账号{}】记录自动回复失败", accountId, e);
        }
    }
    
    @Override
    public com.xianyusmart.controller.dto.AutoDeliveryRecordRespDTO getAutoDeliveryRecords(
            com.xianyusmart.controller.dto.AutoDeliveryRecordReqDTO reqDTO) {
        
        Long accountId = reqDTO.getXianyuAccountId();
        String xyGoodsId = reqDTO.getXyGoodsId();
        String keyword = reqDTO.getKeyword();
        int pageNum = Math.max(reqDTO.getPageNum() != null ? reqDTO.getPageNum() : 1, 1);
        int pageSize = Math.min(Math.max(reqDTO.getPageSize() != null ? reqDTO.getPageSize() : 20, 1), 100);
        List<String> deliveryStatuses = reqDTO.getDeliveryStatuses() == null ? List.of() :
                reqDTO.getDeliveryStatuses().stream().map(status -> {
                    try {
                        return DeliveryStatus.valueOf(status).name();
                    } catch (Exception e) {
                        throw new IllegalArgumentException("无效的履约状态: " + status);
                    }
                }).distinct().toList();
        
        // 使用长整型计算偏移，避免异常页码触发整数溢出。
        long offset = (long) (pageNum - 1) * pageSize;
        
        List<XianyuGoodsOrder> records = orderMapper.selectByAccountIdWithPage(
                accountId, xyGoodsId, keyword, deliveryStatuses, pageSize, offset);
        
        long total = orderMapper.countByAccountId(accountId, xyGoodsId, keyword, deliveryStatuses);
        
        List<com.xianyusmart.controller.dto.AutoDeliveryRecordDTO> recordDTOs = new ArrayList<>();
        for (XianyuGoodsOrder record : records) {
            com.xianyusmart.controller.dto.AutoDeliveryRecordDTO dto = 
                    new com.xianyusmart.controller.dto.AutoDeliveryRecordDTO();
            dto.setId(record.getId());
            dto.setXianyuAccountId(record.getXianyuAccountId());
            dto.setXyGoodsId(record.getXyGoodsId());
            dto.setGoodsTitle(record.getGoodsTitle());
            dto.setBuyerUserName(record.getBuyerUserName());
            dto.setContent(record.getContent());
            dto.setState(record.getState());
            dto.setDeliveryStatus(record.getDeliveryStatus());
            dto.setFailReason(record.getFailReason() != null && !record.getFailReason().isBlank()
                    ? record.getFailReason() : record.getLastErrorMessage());
            dto.setConfirmState(record.getConfirmState());
            dto.setRateStatus(record.getRateStatus());
            dto.setRateTime(record.getRateTime());
            dto.setRateContent(record.getRateContent());
            dto.setRateSource(record.getRateSource());
            dto.setOrderId(record.getOrderId());
            dto.setSkuName(record.getSkuName());
            dto.setOrderCreateTime(record.getOrderCreateTime());
            dto.setPaySuccessTime(record.getPaySuccessTime());
            dto.setConsignTime(record.getConsignTime());
            dto.setTotalPrice(record.getTotalPrice());
            dto.setBuyNum(record.getBuyNum());
            dto.setCreateTime(record.getCreateTime());
            recordDTOs.add(dto);
        }
        
        com.xianyusmart.controller.dto.AutoDeliveryRecordRespDTO respDTO = 
                new com.xianyusmart.controller.dto.AutoDeliveryRecordRespDTO();
        respDTO.setRecords(recordDTOs);
        respDTO.setTotal(total);
        respDTO.setPageNum(pageNum);
        respDTO.setPageSize(pageSize);
        
        return respDTO;
    }

    @Override
    public com.xianyusmart.common.ResultObject<String> triggerAutoDelivery(
            com.xianyusmart.controller.dto.TriggerAutoDeliveryReqDTO reqDTO) {
        try {
            Long accountId = reqDTO.getXianyuAccountId();
            String xyGoodsId = reqDTO.getXyGoodsId();
            String orderId = reqDTO.getOrderId();
            Boolean needHumanLikeDelay = reqDTO.getNeedHumanLikeDelay() != null ? reqDTO.getNeedHumanLikeDelay() : false;

            log.info("【账号{}】触发自动发货: xyGoodsId={}, orderId={}, needHumanLikeDelay={}", 
                    accountId, xyGoodsId, orderId, needHumanLikeDelay);

            XianyuGoodsOrder record = orderMapper.selectByOrderId(accountId, xyGoodsId, orderId);
            if (record == null) {
                log.warn("【账号{}】发货记录不存在: orderId={}", accountId, orderId);
                return com.xianyusmart.common.ResultObject.failed("发货记录不存在");
            }

            String pnmId = record.getPnmId();
            if (pnmId == null || pnmId.isEmpty()) {
                log.warn("【账号{}】发货记录没有pnmId: orderId={}", accountId, orderId);
                return com.xianyusmart.common.ResultObject.failed("发货记录没有pnmId");
            }

            Long recordId = record.getId();
            String sId = record.getSid() != null ? record.getSid() : record.getBuyerUserId() + "@goofish";
            String buyerUserName = record.getBuyerUserName();

            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.info("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                return com.xianyusmart.common.ResultObject.failed("商品未开启自动发货");
            }

            executeDelivery(recordId, accountId, xyGoodsId, sId, orderId, buyerUserName, needHumanLikeDelay);

            XianyuGoodsOrder updatedRecord = orderMapper.selectByOrderId(accountId, xyGoodsId, orderId);
            if (updatedRecord != null && updatedRecord.getState() == 1) {
                return com.xianyusmart.common.ResultObject.success("触发自动发货成功");
            } else {
                String failReason = updatedRecord != null ? updatedRecord.getFailReason() : "未知错误";
                return com.xianyusmart.common.ResultObject.failed(failReason != null ? failReason : "发货失败");
            }

        } catch (Exception e) {
            log.error("【账号{}】触发自动发货失败: xyGoodsId={}, orderId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getOrderId(), e);
            return com.xianyusmart.common.ResultObject.failed("触发自动发货失败: " + e.getMessage());
        }
    }

    @Override
    public void executeDelivery(Long recordId, Long accountId, String xyGoodsId, String sId, String orderId, String buyerUserName, boolean needHumanLikeDelay) {
        boolean cardDelivery = false;
        boolean cardDeliveryAttempted = false;
        boolean anySuccess = false;
        StringBuilder allContent = new StringBuilder();
        try {
            log.info("【账号{}】开始执行自动发货: recordId={}, xyGoodsId={}, orderId={}", accountId, recordId, xyGoodsId, orderId);

            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.warn("【账号{}】商品未开启自动发货: xyGoodsId={}", accountId, xyGoodsId);
                updateRecordState(recordId, -1, null, "商品未开启自动发货");
                return;
            }

            OrderDetailFetcher.OrderDetailInfo orderDetail = orderDetailFetcher.fetch(accountId, xyGoodsId, orderId);
            if (orderDetail == null && orderId != null && !orderId.isEmpty()) {
                log.warn("【账号{}】获取订单详情失败(可能Cookie过期或API异常)，中断发货: orderId={}", accountId, orderId);
                String failReason = "获取订单详情失败(可能Cookie过期)，请检查Cookie状态";
                updateRecordState(recordId, -1, null, failReason);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failReason);
                return;
            }
            String orderSkuId = orderDetail != null ? orderDetail.skuId : null;
            int buyNum = (orderDetail != null && orderDetail.buyNum != null && orderDetail.buyNum > 0) ? orderDetail.buyNum : 1;
            log.info("【账号{}】订单SKU: orderId={}, skuId={}, buyNum={}", accountId, orderId, orderSkuId, buyNum);

            if (orderDetail != null) {
                orderMapper.updateOrderDetail(recordId, orderDetail.buyerUserName, orderDetail.orderCreateTime, orderDetail.paySuccessTime, orderDetail.consignTime, orderDetail.skuName, orderDetail.skuId, orderDetail.goodsTitle, orderDetail.totalPrice, orderDetail.buyNum);
            }

            XianyuGoodsAutoDeliveryConfig deliveryConfig = null;
            if (orderSkuId != null && !orderSkuId.isEmpty()) {
                deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(accountId, xyGoodsId, orderSkuId);
            }
            if (deliveryConfig == null) {
                deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
            }

            if (deliveryConfig == null) {
                log.warn("【账号{}】商品无匹配的发货配置: xyGoodsId={}, skuId={}", accountId, xyGoodsId, orderSkuId);
                updateRecordState(recordId, -1, null, "无匹配的发货配置");
                return;
            }

            int deliveryMode = deliveryConfig.getDeliveryMode() != null ? deliveryConfig.getDeliveryMode() : 1;
            cardDelivery = (deliveryMode & 2) == 2;
            String cid = sId.replace("@goofish", "");

            DeliveryContext ctx = DeliveryContext.builder()
                    .recordId(recordId)
                    .accountId(accountId)
                    .xyGoodsId(xyGoodsId)
                    .sId(sId)
                    .orderId(orderId)
                    .buyerUserName(buyerUserName)
                    .quantity(buyNum)
                    .deliveryConfig(deliveryConfig)
                    .build();

            String content = deliveryStrategyResolver.resolve(deliveryMode, ctx);
            if (content == null) {
                String failMsg = deliveryMode == 1 ? "未配置固定发货内容"
                        : deliveryMode == 2 ? "卡密库存不足，无可用卡密"
                        : "固定内容未配置或卡密库存不足";
                log.warn("【账号{}】发货内容解析失败: {}", accountId, failMsg);
                updateRecordState(recordId, -1, null, failMsg);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                return;
            }

            if (content.length() > 200) {
                if (cardDelivery) {
                    kamiConfigService.releaseReservation(orderId);
                }
                String failMsg = "发货内容超过凭证接口200字符限制，请缩短发货内容或模板";
                updateRecordState(recordId, -1, null, failMsg);
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failMsg);
                return;
            }

            List<String> imageUrls = new ArrayList<>();
            String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
            if (imageUrlStr != null && !imageUrlStr.trim().isEmpty()) {
                for (String url : imageUrlStr.split(",")) {
                    String trimmed = url.trim();
                    if (!trimmed.isEmpty()) imageUrls.add(trimmed);
                }
            }

            // 实际发货内容只解析一次，同时用于凭证和私聊，防止卡密重复扣减。
            cardDeliveryAttempted = cardDelivery;
            String deliveryResult = orderService.consignDummyDelivery(accountId, orderId, content, imageUrls);
            if (OrderService.CONSIGN_UNCERTAIN.equals(deliveryResult)) {
                String failReason = "发货结果待确认，请核对平台凭证后处理";
                if (cardDelivery) {
                    // 外部接口结果不确定时锁定原卡密，避免重试后向同一订单分配不同内容。
                    kamiConfigService.markReservationReviewRequired(orderId);
                }
                updateRecordState(recordId, -1, content, failReason);
                orderMapper.markTaskReviewRequired(recordId, failReason);
                log.warn("【账号{}】发货结果待确认: recordId={}", accountId, recordId);
                return;
            }
            if (OrderService.CONSIGN_ALREADY_DELIVERED.equals(deliveryResult)) {
                String failReason = "订单已存在发货凭证，请核对凭证与私聊内容";
                if (cardDelivery) {
                    // 已存在凭证时无法确认首次请求是否使用当前卡密，必须锁定等待核对。
                    kamiConfigService.markReservationReviewRequired(orderId);
                }
                updateRecordState(recordId, -1, content, failReason);
                orderMapper.markTaskReviewRequired(recordId, failReason);
                log.warn("【账号{}】订单已存在发货凭证: recordId={}", accountId, recordId);
                return;
            }
            if (!OrderService.CONSIGN_SUCCESS.equals(deliveryResult)) {
                if (cardDelivery) {
                    kamiConfigService.releaseReservation(orderId);
                }
                log.error("【账号{}】❌ 发货凭证提交失败: recordId={}", accountId, recordId);
                updateRecordState(recordId, -1, content, "发货凭证提交失败");
                emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, "发货凭证提交失败");
                return;
            }

            anySuccess = true;
            allContent.append(content);
            orderMapper.updateConfirmState(accountId, orderId);
            if (cardDelivery) {
                kamiConfigService.commitReservation(orderId, accountId, xyGoodsId, cid, buyerUserName);
            }

            if (needHumanLikeDelay) {
                HumanLikeDelayUtils.mediumDelay();
                HumanLikeDelayUtils.typingDelay(content.length());
            }

            XianyuGoodsOrder deliveryOrder = orderMapper.selectById(recordId);
            if (deliveryOrder != null) {
                if (deliveryOrder.getSid() == null || deliveryOrder.getSid().isBlank()) {
                    deliveryOrder.setSid(sId);
                }
                if ((deliveryOrder.getBuyerUserName() == null || deliveryOrder.getBuyerUserName().isBlank())
                        && orderDetail != null) {
                    deliveryOrder.setBuyerUserName(orderDetail.buyerUserName);
                }
                boolean messageSent = buyerMessageService.queueDeliveryMessage(deliveryOrder, deliveryConfig, content);
                if (messageSent) {
                    sendDeliveryImages(accountId, xyGoodsId, cid, cid, deliveryConfig, needHumanLikeDelay);
                } else {
                    log.info("【账号{}】发货私聊已进入重试队列: orderId={}", accountId, orderId);
                }
            }
            log.info("【账号{}】✅ 发货凭证提交成功并已处理私聊: recordId={}, deliveryMode={}",
                    accountId, recordId, deliveryMode);

            if (anySuccess) {
                updateRecordState(recordId, 1, allContent.toString(), null);
            }

        } catch (Exception e) {
            if (cardDelivery) {
                if (cardDeliveryAttempted) {
                    kamiConfigService.markReservationReviewRequired(orderId);
                } else {
                    kamiConfigService.releaseReservation(orderId);
                }
            }
            log.error("【账号{}】执行自动发货异常: recordId={}, xyGoodsId={}", accountId, recordId, xyGoodsId, e);
            if (anySuccess) {
                // 外部发货凭证已提交，后续异常不能把已履约订单回写为失败。
                updateRecordState(recordId, 1, allContent.toString(), null);
                return;
            }
            String failReason = "发货异常: " + e.getMessage();
            updateRecordState(recordId, -1, null, failReason);
            emailNotifyService.sendAutoDeliveryFailEmail(null, xyGoodsId, orderId, failReason);
        }
    }

    private void sendDeliveryImages(Long accountId, String xyGoodsId, String cid, String toId,
                                    XianyuGoodsAutoDeliveryConfig deliveryConfig, boolean needHumanLikeDelay) {
        String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
        if (imageUrlStr == null || imageUrlStr.trim().isEmpty()) {
            return;
        }
        String[] imageUrls = imageUrlStr.split(",");
        for (int i = 0; i < imageUrls.length; i++) {
            try {
                String url = imageUrls[i].trim();
                if (url.isEmpty()) continue;
                if (i > 0) {
                    if (needHumanLikeDelay) {
                        HumanLikeDelayUtils.thinkingDelay();
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                boolean imgSuccess = webSocketService.sendImageMessage(accountId, cid, toId, url, 800, 800);
                if (imgSuccess) {
                    log.info("【账号{}】自动发货图片[{}/{}]发送成功: xyGoodsId={}", accountId, i + 1, imageUrls.length, xyGoodsId);
                    sentMessageSaveService.saveManualImageReply(accountId, cid, toId, url, xyGoodsId);
                } else {
                    log.warn("【账号{}】自动发货图片[{}/{}]发送失败: xyGoodsId={}", accountId, i + 1, imageUrls.length, xyGoodsId);
                }
            } catch (Exception e) {
                log.error("【账号{}】自动发货图片[{}/{}]发送异常: xyGoodsId={}", accountId, i + 1, imageUrls.length, xyGoodsId, e);
            }
        }
    }

    private void executeAutoConfirmShipment(Long accountId, String orderId) {
        if (orderId == null || orderId.isEmpty()) {
            log.warn("【账号{}】订单ID为空，无法自动确认发货", accountId);
            return;
        }
        log.info("【账号{}】提交异步自动确认发货: orderId={}", accountId, orderId);
        taskExecutor.execute(() -> {
            try {
                HumanLikeDelayUtils.longDelay();
                String result = orderService.confirmShipment(accountId, orderId);
                if (result != null) {
                    log.info("【账号{}】✅ 自动确认发货成功: orderId={}", accountId, orderId);
                    orderMapper.updateConfirmState(accountId, orderId);
                } else {
                    log.error("【账号{}】❌ 自动确认发货失败: orderId={}", accountId, orderId);
                }
            } catch (Exception e) {
                log.error("【账号{}】自动确认发货异常: orderId={}", accountId, orderId, e);
            }
        });
    }

    private void updateRecordState(Long recordId, Integer state, String content, String failReason) {
        try {
            orderMapper.updateStateContentAndFailReason(recordId, state, content, failReason);
        } catch (Exception e) {
            log.error("更新订单状态失败: recordId={}, state={}", recordId, state, e);
        }
    }

    @Override
    public void updateAutoConfirmShipment(Long accountId, String xyGoodsId, Integer autoConfirmShipment) {
        XianyuGoodsAutoDeliveryConfig config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
        if (config == null) {
            config = new XianyuGoodsAutoDeliveryConfig();
            config.setXianyuAccountId(accountId);
            config.setXyGoodsId(xyGoodsId);
            config.setAutoConfirmShipment(autoConfirmShipment);
            autoDeliveryConfigMapper.insert(config);
        } else {
            config.setAutoConfirmShipment(autoConfirmShipment);
            autoDeliveryConfigMapper.updateById(config);
        }
    }

    @Override
    public com.xianyusmart.common.ResultObject<String> manualDelivery(Long xianyuAccountId, String orderId, String content) {
        try {
            if (orderId == null || orderId.isEmpty()) {
                return com.xianyusmart.common.ResultObject.failed("订单ID不能为空");
            }
            if (content == null || content.trim().isEmpty()) {
                return com.xianyusmart.common.ResultObject.failed("发货内容不能为空");
            }

            XianyuGoodsOrder record = orderMapper.selectByAccountIdAndOrderId(xianyuAccountId, orderId);
            if (record == null) {
                return com.xianyusmart.common.ResultObject.failed("订单记录不存在");
            }

            String sId = record.getSid() != null ? record.getSid() : record.getBuyerUserId() + "@goofish";
            String cid = sId.replace("@goofish", "");
            String toId = cid;

            boolean success = webSocketService.sendMessage(xianyuAccountId, cid, toId, content);
            if (success) {
                updateRecordState(record.getId(), 1, content, null);
                sentMessageSaveService.saveAiAssistantReply(xianyuAccountId, cid, toId, content, record.getXyGoodsId());
                log.info("【账号{}】自定义发货成功: orderId={}", xianyuAccountId, orderId);
                return com.xianyusmart.common.ResultObject.success("自定义发货成功");
            } else {
                updateRecordState(record.getId(), -1, content, "消息发送失败");
                log.error("【账号{}】自定义发货失败: orderId={}", xianyuAccountId, orderId);
                return com.xianyusmart.common.ResultObject.failed("消息发送失败");
            }
        } catch (Exception e) {
            log.error("【账号{}】自定义发货异常: orderId={}", xianyuAccountId, orderId, e);
            return com.xianyusmart.common.ResultObject.failed("自定义发货异常: " + e.getMessage());
        }
    }
}
