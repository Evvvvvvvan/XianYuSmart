package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.controller.dto.ItemDTO;
import com.xianyusmart.controller.dto.SyncProgressRespDTO;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.GoodsInfoService;
import com.xianyusmart.service.GoodsSkuService;
import com.xianyusmart.service.GoodsSkuPropertyService;
import com.xianyusmart.service.ItemDetailSyncService;
import com.xianyusmart.utils.ItemDetailUtils;
import com.xianyusmart.utils.XianyuApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

@Slf4j
@Service
public class ItemDetailSyncServiceImpl implements ItemDetailSyncService {

    @Autowired
    private AccountService accountService;

    @Autowired
    private GoodsInfoService goodsInfoService;

    @Autowired
    private GoodsSkuService goodsSkuService;

    @Autowired
    private GoodsSkuPropertyService goodsSkuPropertyService;

    @Autowired
    private ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, SyncProgress> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> accountSyncMap = new ConcurrentHashMap<>();

    private static class SyncProgress {
        String syncId;
        Long accountId;
        int totalCount;
        int completedCount = 0;
        int successCount = 0;
        int failedCount = 0;
        boolean isCompleted = false;
        boolean isRunning = true;
        String currentItemId = null;
        String message = "同步中...";
        long startTime;
        boolean cancelled = false;
    }

    @Override
    public String startSync(Long accountId, List<ItemDTO> items) {
        if (isSyncing(accountId)) {
            String existingSyncId = accountSyncMap.get(accountId);
            log.info("账号已有同步任务进行中: accountId={}, syncId={}", accountId, existingSyncId);
            return existingSyncId;
        }

        String syncId = UUID.randomUUID().toString();
        SyncProgress progress = new SyncProgress();
        progress.syncId = syncId;
        progress.accountId = accountId;
        progress.totalCount = items.size();
        progress.startTime = System.currentTimeMillis();

        progressMap.put(syncId, progress);
        accountSyncMap.put(accountId, syncId);

        String cookieStr = accountService.getCookieByAccountId(accountId);

        executeSync(syncId, accountId, items, cookieStr);

        log.info("启动异步详情同步: syncId={}, accountId={}, itemCount={}", syncId, accountId, items.size());
        return syncId;
    }

    @Async
    public void executeSync(String syncId, Long accountId, List<ItemDTO> items, String cookieStr) {
        SyncProgress progress = progressMap.get(syncId);
        if (progress == null) {
            log.error("同步进度不存在: syncId={}", syncId);
            return;
        }

        try {
            for (ItemDTO item : items) {
                if (progress.cancelled) {
                    progress.message = "同步已取消";
                    break;
                }

                String itemId = item.getDetailParams() != null ? item.getDetailParams().getItemId() : item.getId();
                if (itemId == null || itemId.isEmpty()) {
                    progress.completedCount++;
                    progress.failedCount++;
                    continue;
                }

                progress.currentItemId = itemId;

                try {
                    Thread.sleep(new Random().nextInt(501));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                boolean success = fetchAndSaveDetail(itemId, cookieStr, accountId);
                
                progress.completedCount++;
                if (success) {
                    progress.successCount++;
                } else {
                    progress.failedCount++;
                }

                progress.message = String.format("同步进度: %d/%d", progress.completedCount, progress.totalCount);
            }

            progress.isCompleted = true;
            progress.isRunning = false;
            progress.currentItemId = null;
            progress.message = String.format("同步完成: 成功%d, 失败%d", progress.successCount, progress.failedCount);

        } catch (Exception e) {
            log.error("异步同步异常: syncId={}", syncId, e);
            progress.isCompleted = true;
            progress.isRunning = false;
            progress.message = "同步失败: " + e.getMessage();
        } finally {
            accountSyncMap.remove(accountId);
        }
    }

    private boolean fetchAndSaveDetail(String itemId, String cookieStr, Long accountId) {
        try {
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("itemId", itemId);

            String response = XianyuApiUtils.callApi(
                "mtop.taobao.idle.pc.detail",
                dataMap,
                cookieStr
            );

            if (response == null) {
                log.warn("获取商品详情失败，响应为空: itemId={}", itemId);
                return false;
            }

            log.debug("mtop.taobao.idle.pc.detail 响应: itemId={}, response={}", itemId, response);
            JsonNode itemDONode = readSuccessfulItem(response, itemId);
            if (itemDONode == null) {
                log.warn("商品详情同步被平台拒绝: itemId={}", itemId);
                return false;
            }

            String extractedDesc = itemDONode.path("desc").asText("");
            
            if (!extractedDesc.isEmpty()
                    && !goodsInfoService.updateDetailInfo(accountId, itemId, extractedDesc)) {
                return false;
            }

            JsonNode skuDataNode = itemDONode.get("skuList");
            if (skuDataNode == null || !skuDataNode.isArray()) {
                skuDataNode = itemDONode.get("idleItemSkuList");
            }
            List<XianyuGoodsSku> skuList = ItemDetailUtils.extractSkuList(response);
            if (!skuList.isEmpty()) {
                goodsSkuService.saveSkus(itemId, accountId, skuList);
                if (!goodsInfoService.updateSkuCount(accountId, itemId, skuList.size())) {
                    return false;
                }
                List<XianyuGoodsSkuProperty> propertyList = ItemDetailUtils.extractSkuPropertyList(response);
                if (!propertyList.isEmpty()) {
                    goodsSkuPropertyService.saveProperties(itemId, accountId, propertyList);
                }
            } else if (skuDataNode != null && skuDataNode.isArray() && skuDataNode.isEmpty()) {
                // 平台明确返回空SKU时才清理，避免异常或截断响应误删现有规格。
                goodsSkuService.deleteByXyGoodsId(itemId, accountId);
                goodsSkuPropertyService.deleteByXyGoodsId(itemId, accountId);
                if (!goodsInfoService.updateSkuCount(accountId, itemId, 0)) {
                    return false;
                }
            }

            log.debug("商品详情同步成功: itemId={}", itemId);
            return true;

        } catch (Exception e) {
            log.error("获取商品详情异常: itemId={}", itemId, e);
            return false;
        }
    }

    private JsonNode readSuccessfulItem(String response, String itemId) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode retNode = rootNode.path("ret");
            if (!retNode.isArray() || retNode.isEmpty()
                    || !retNode.get(0).asText("").startsWith("SUCCESS")) {
                return null;
            }
            JsonNode itemDONode = rootNode.path("data").path("itemDO");
            if (!itemDONode.isObject()) {
                return null;
            }
            String responseItemId = itemDONode.path("itemId").asText("");
            if (responseItemId.isBlank()) {
                responseItemId = itemDONode.path("id").asText("");
            }
            return itemId.equals(responseItemId) ? itemDONode : null;
        } catch (Exception e) {
            log.warn("商品详情响应解析失败", e);
            return null;
        }
    }

    @Override
    public boolean syncSingleItem(Long accountId, String itemId) {
        if (accountId == null || itemId == null || itemId.isEmpty()) {
            log.warn("同步单个商品参数无效: accountId={}, itemId={}", accountId, itemId);
            return false;
        }
        String cookieStr = accountService.getCookieByAccountId(accountId);
        if (cookieStr == null || cookieStr.isEmpty()) {
            log.warn("账号Cookie不存在: accountId={}", accountId);
            return false;
        }
        log.info("同步单个商品: accountId={}, itemId={}", accountId, itemId);
        return fetchAndSaveDetail(itemId, cookieStr, accountId);
    }

    @Override
    public SyncProgressRespDTO getProgress(String syncId) {
        SyncProgress progress = progressMap.get(syncId);
        if (progress == null) {
            return null;
        }

        SyncProgressRespDTO dto = new SyncProgressRespDTO();
        dto.setSyncId(progress.syncId);
        dto.setAccountId(progress.accountId);
        dto.setTotalCount(progress.totalCount);
        dto.setCompletedCount(progress.completedCount);
        dto.setSuccessCount(progress.successCount);
        dto.setFailedCount(progress.failedCount);
        dto.setIsCompleted(progress.isCompleted);
        dto.setIsRunning(progress.isRunning);
        dto.setCurrentItemId(progress.currentItemId);
        dto.setMessage(progress.message);
        dto.setStartTime(progress.startTime);

        if (progress.completedCount > 0 && progress.totalCount > 0) {
            long elapsed = System.currentTimeMillis() - progress.startTime;
            long avgTimePerItem = elapsed / progress.completedCount;
            long remainingItems = progress.totalCount - progress.completedCount;
            dto.setEstimatedRemainingTime(avgTimePerItem * remainingItems);
        }

        return dto;
    }

    @Override
    public void cancelSync(String syncId) {
        SyncProgress progress = progressMap.get(syncId);
        if (progress != null) {
            progress.cancelled = true;
            progress.message = "正在取消同步...";
            log.info("取消同步: syncId={}", syncId);
        }
    }

    @Override
    public boolean isSyncing(Long accountId) {
        String syncId = accountSyncMap.get(accountId);
        if (syncId == null) {
            return false;
        }
        SyncProgress progress = progressMap.get(syncId);
        return progress != null && progress.isRunning && !progress.isCompleted;
    }
}
