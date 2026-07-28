package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiExternalRequest;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.enums.KamiStatus;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiExternalRequestMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.service.kami.ExternalKamiGateway;
import com.xianyusmart.service.kami.ExternalKamiResponseParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 外部卡密供货协调服务
 */
@Service
public class ExternalKamiProvisionService {

    private final XianyuKamiExternalRequestMapper requestMapper;
    private final XianyuKamiItemMapper itemMapper;
    private final XianyuKamiConfigMapper configMapper;
    private final ExternalKamiGateway gateway;
    private final ExternalKamiResponseParser responseParser;
    private final TransactionTemplate transactionTemplate;

    public ExternalKamiProvisionService(XianyuKamiExternalRequestMapper requestMapper,
                                        XianyuKamiItemMapper itemMapper,
                                        XianyuKamiConfigMapper configMapper,
                                        ExternalKamiGateway gateway,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.requestMapper = requestMapper;
        this.itemMapper = itemMapper;
        this.configMapper = configMapper;
        this.gateway = gateway;
        this.responseParser = new ExternalKamiResponseParser(objectMapper);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<XianyuKamiItem> reserve(XianyuKamiConfig config, String orderId, int quantity) {
        // 短事务锁定配置并建立幂等请求，配置修改或删除无法与供货请求交叉执行。
        Preparation preparation = transactionTemplate.execute(status ->
                prepare(config.getId(), orderId, quantity));
        if (preparation == null) {
            throw new BusinessException(409, "外部卡密请求准备失败");
        }
        if (preparation.existingItems() != null) {
            return preparation.existingItems();
        }
        XianyuKamiConfig currentConfig = preparation.config();
        XianyuKamiExternalRequest request = preparation.request();

        try {
            String responseBody = gateway.request(
                    currentConfig, orderId, quantity, request.getRequestToken());
            List<String> contents = responseParser.parse(
                    responseBody, currentConfig.getExternalApiResultPath(), quantity);
            XianyuKamiExternalRequest finalRequest = request;
            List<XianyuKamiItem> items = transactionTemplate.execute(status ->
                    saveReservedItems(currentConfig, orderId, contents, finalRequest));
            if (items == null) {
                throw new BusinessException(409, "外部卡密保存失败");
            }
            return items;
        } catch (ExternalKamiGateway.ExternalKamiException e) {
            String requestStatus = e.isUncertain() ? "REVIEW_REQUIRED" : "FAILED";
            requestMapper.markFailure(request.getId(), requestStatus, limit(e.getMessage(), 500));
            throw new BusinessException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            requestMapper.markFailure(request.getId(), "REVIEW_REQUIRED", limit(e.getMessage(), 500));
            throw new BusinessException(409, e.getMessage());
        } catch (RuntimeException e) {
            requestMapper.markFailure(request.getId(), "REVIEW_REQUIRED",
                    limit(e.getMessage() == null ? "外部卡密保存结果需要人工核对" : e.getMessage(), 500));
            throw new BusinessException(409, "外部卡密保存结果需要人工核对");
        }
    }

    private Preparation prepare(Long configId, String orderId, int quantity) {
        XianyuKamiConfig config = configMapper.lockById(configId);
        if (config == null) {
            throw new BusinessException(404, "卡密配置不存在");
        }
        if (!"API".equalsIgnoreCase(config.getSourceType())) {
            throw new BusinessException(409, "卡密供货来源已变更，请重新提交订单");
        }
        XianyuKamiExternalRequest request = createRequest(config, orderId, quantity);
        boolean ownsRequest = requestMapper.insertIfAbsent(request) == 1;
        if (!ownsRequest) {
            request = requestMapper.findByOrder(config.getId(), orderId);
            if (request == null) {
                throw new BusinessException(409, "外部卡密请求状态异常");
            }
            if (!Integer.valueOf(quantity).equals(request.getQuantity())) {
                throw new BusinessException(409, "订单卡密数量与已有外部供货请求不一致");
            }
            if ("SUCCESS".equals(request.getRequestStatus())) {
                List<XianyuKamiItem> existing = itemMapper.findByOrderAndStatus(
                        orderId, KamiStatus.RESERVED.getCode());
                if (existing.size() == quantity) {
                    return new Preparation(config, request, existing);
                }
                throw new BusinessException(409, "外部卡密记录与预占数量不一致，需要人工核对");
            }
            if (!"FAILED".equals(request.getRequestStatus())
                    && !isStaleProcessing(request)) {
                throw new BusinessException(409, "外部卡密正在获取或等待人工核对");
            }
            if (requestMapper.claimRetry(request.getId()) != 1) {
                throw new BusinessException(409, "外部卡密请求无法重试，需要人工核对");
            }
            request = requestMapper.findByOrder(config.getId(), orderId);
        } else {
            request = requestMapper.findByOrder(config.getId(), orderId);
        }
        if (request == null) {
            throw new BusinessException(409, "外部卡密请求状态异常");
        }
        return new Preparation(config, request, null);
    }

    private List<XianyuKamiItem> saveReservedItems(XianyuKamiConfig config, String orderId,
                                                   List<String> contents,
                                                   XianyuKamiExternalRequest request) {
        for (int index = 0; index < contents.size(); index++) {
            XianyuKamiItem item = new XianyuKamiItem();
            item.setKamiConfigId(config.getId());
            item.setKamiContent(contents.get(index));
            item.setStatus(KamiStatus.RESERVED.getCode());
            item.setOrderId(orderId);
            item.setReservedTime(LocalDateTime.now());
            item.setSortOrder(index);
            itemMapper.insert(item);
        }
        if (requestMapper.markSuccess(request.getId(), "received " + contents.size() + " item(s)") != 1) {
            throw new IllegalStateException("外部卡密请求状态已变化");
        }
        return itemMapper.findByOrderAndStatus(orderId, KamiStatus.RESERVED.getCode());
    }

    private XianyuKamiExternalRequest createRequest(XianyuKamiConfig config, String orderId, int quantity) {
        XianyuKamiExternalRequest request = new XianyuKamiExternalRequest();
        request.setTenantId(TenantContext.get() == null ? UserContext.getUserId() : TenantContext.get());
        request.setKamiConfigId(config.getId());
        request.setXianyuAccountId(config.getXianyuAccountId());
        request.setOrderId(orderId);
        request.setRequestToken(UUID.randomUUID().toString().replace("-", ""));
        request.setQuantity(quantity);
        return request;
    }

    private boolean isStaleProcessing(XianyuKamiExternalRequest request) {
        return "PROCESSING".equals(request.getRequestStatus())
                && request.getUpdateTime() != null
                && request.getUpdateTime().isBefore(LocalDateTime.now().minusMinutes(2));
    }

    private String limit(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maxLength));
    }

    private record Preparation(XianyuKamiConfig config,
                               XianyuKamiExternalRequest request,
                               List<XianyuKamiItem> existingItems) {
    }
}
