package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.BuyerProfileQueryReqDTO;
import com.xianyusmart.controller.dto.BuyerMessageDTO;
import com.xianyusmart.controller.dto.BuyerProfileDetailReqDTO;
import com.xianyusmart.controller.dto.BuyerProfileDetailRespDTO;
import com.xianyusmart.controller.dto.BuyerProfileRespDTO;
import com.xianyusmart.controller.dto.BuyerProfileSaveReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuBuyerProfile;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuBuyerProfileMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.buyer.BuyerProfilePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家关系与自动化拦截服务
 */
@Service
public class BuyerProfileService {

    private final XianyuBuyerProfileMapper profileMapper;
    private final XianyuAccountMapper accountMapper;
    private final ObjectMapper objectMapper;
    private final XianyuGoodsOrderMapper orderMapper;
    private final XianyuChatMessageMapper messageMapper;

    public BuyerProfileService(XianyuBuyerProfileMapper profileMapper,
                               XianyuAccountMapper accountMapper,
                               ObjectMapper objectMapper,
                               XianyuGoodsOrderMapper orderMapper,
                               XianyuChatMessageMapper messageMapper) {
        this.profileMapper = profileMapper;
        this.accountMapper = accountMapper;
        this.objectMapper = objectMapper;
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
    }

    public Map<String, Object> list(BuyerProfileQueryReqDTO request) {
        validateOwnedAccount(request.getXianyuAccountId(), false);
        int pageNum = request.getPageNum() == null ? 1 : Math.max(1, request.getPageNum());
        int pageSize = request.getPageSize() == null ? 30 : Math.max(1, Math.min(100, request.getPageSize()));
        Integer blocked = request.getAutomationBlocked() == null ? null
                : (request.getAutomationBlocked() ? 1 : 0);
        String keyword = trimToNull(request.getKeyword());
        List<BuyerProfileRespDTO> records = profileMapper.selectPage(
                request.getXianyuAccountId(), keyword, blocked, pageSize, (long) (pageNum - 1) * pageSize);
        records.forEach(this::readTags);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", profileMapper.countPage(request.getXianyuAccountId(), keyword, blocked));
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    @Transactional
    public BuyerProfileRespDTO save(BuyerProfileSaveReqDTO request) {
        validateOwnedAccount(request.getXianyuAccountId(), true);
        String buyerUserId = request.getBuyerUserId().trim();
        XianyuBuyerProfile profile = profileMapper.findByBuyer(request.getXianyuAccountId(), buyerUserId);
        if (profile == null) {
            profile = new XianyuBuyerProfile();
            profile.setTenantId(requireTenantId());
            profile.setXianyuAccountId(request.getXianyuAccountId());
            profile.setBuyerUserId(buyerUserId);
        }
        String buyerUserName = trimToNull(request.getBuyerUserName());
        String note = trimToNull(request.getNote());
        String blockedReason = trimToNull(request.getBlockedReason());
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("买家备注不能超过500个字符");
        }
        if (blockedReason != null && blockedReason.length() > 200) {
            throw new IllegalArgumentException("拦截原因不能超过200个字符");
        }
        profile.setBuyerUserName(buyerUserName);
        profile.setTagsJson(writeTags(BuyerProfilePolicy.normalizeTags(request.getTags())));
        profile.setNote(note);
        profile.setAutomationBlocked(Boolean.TRUE.equals(request.getAutomationBlocked()) ? 1 : 0);
        profile.setBlockedReason(blockedReason);
        if (profile.getId() == null) {
            profileMapper.insert(profile);
        } else {
            profileMapper.updateById(profile);
        }
        BuyerProfileQueryReqDTO query = new BuyerProfileQueryReqDTO();
        query.setXianyuAccountId(profile.getXianyuAccountId());
        query.setKeyword(profile.getBuyerUserId());
        query.setPageSize(1);
        @SuppressWarnings("unchecked")
        List<BuyerProfileRespDTO> records = (List<BuyerProfileRespDTO>) list(query).get("records");
        return records.isEmpty() ? null : records.getFirst();
    }

    public void touch(Long accountId, String buyerUserId, String buyerUserName, Long messageTime) {
        if (accountId == null || buyerUserId == null || buyerUserId.isBlank()) {
            return;
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return;
        }
        LocalDateTime interactionTime = messageTime == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(messageTime), ZoneId.systemDefault());
        profileMapper.touch(account.getTenantId(), accountId, buyerUserId.trim(), trimToNull(buyerUserName), interactionTime);
    }

    public String automationBlockReason(Long accountId, String buyerUserId) {
        if (accountId == null || buyerUserId == null || buyerUserId.isBlank()) {
            return null;
        }
        XianyuBuyerProfile profile = profileMapper.findByBuyer(accountId, buyerUserId);
        if (profile == null) {
            return null;
        }
        return BuyerProfilePolicy.blockReason(
                Integer.valueOf(1).equals(profile.getAutomationBlocked()), profile.getBlockedReason());
    }

    public BuyerProfileDetailRespDTO detail(BuyerProfileDetailReqDTO request) {
        validateOwnedAccount(request.getXianyuAccountId(), true);
        String buyerUserId = request.getBuyerUserId().trim();
        BuyerProfileRespDTO profile = profileMapper.selectDetail(request.getXianyuAccountId(), buyerUserId);
        if (profile == null) {
            throw new IllegalArgumentException("买家资料不存在");
        }
        readTags(profile);
        List<com.xianyusmart.entity.XianyuGoodsOrder> orders =
                orderMapper.selectByBuyer(request.getXianyuAccountId(), buyerUserId);
        Map<String, List<com.xianyusmart.entity.XianyuGoodsOrder>> sessionOrders = new LinkedHashMap<>();
        orders.forEach(order -> {
            if (order.getSid() == null || order.getSid().isBlank()
                    || order.getOrderId() == null || order.getOrderId().isBlank()) {
                return;
            }
            List<com.xianyusmart.entity.XianyuGoodsOrder> sessionOrderList = sessionOrders.computeIfAbsent(order.getSid(),
                    ignored -> new java.util.ArrayList<>());
            sessionOrderList.add(order);
        });
        List<BuyerMessageDTO> messages = messageMapper
                .findByBuyerAndSessions(request.getXianyuAccountId(), buyerUserId)
                .stream()
                .map(message -> toMessage(message, buyerUserId, sessionOrders))
                .toList();
        BuyerProfileDetailRespDTO detail = new BuyerProfileDetailRespDTO();
        detail.setProfile(profile);
        detail.setOrders(orders);
        detail.setMessages(messages);
        detail.setGoods(profileMapper.selectRelatedGoods(request.getXianyuAccountId(), buyerUserId));
        return detail;
    }

    private void validateOwnedAccount(Long accountId, boolean required) {
        if (accountId == null) {
            if (required) {
                throw new IllegalArgumentException("闲鱼账号ID不能为空");
            }
            return;
        }
        if (accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("闲鱼账号不存在或无权访问");
        }
    }

    private Long requireTenantId() {
        Long tenantId = UserContext.getUserId();
        if (tenantId == null) {
            throw new IllegalStateException("登录状态已失效");
        }
        return tenantId;
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new IllegalArgumentException("买家标签格式无效");
        }
    }

    private void readTags(BuyerProfileRespDTO profile) {
        try {
            profile.setTags(profile.getTagsJson() == null
                    ? List.of()
                    : objectMapper.readValue(profile.getTagsJson(), new TypeReference<>() {
                    }));
        } catch (Exception e) {
            profile.setTags(List.of());
        }
    }

    private BuyerMessageDTO toMessage(com.xianyusmart.entity.XianyuChatMessage message,
                                      String buyerUserId,
                                      Map<String, List<com.xianyusmart.entity.XianyuGoodsOrder>> sessionOrders) {
        BuyerMessageDTO response = new BuyerMessageDTO();
        response.setId(message.getId());
        response.setPnmId(message.getPnmId());
        response.setSid(message.getSId());
        response.setContentType(message.getContentType());
        response.setContent(message.getMsgContent());
        response.setSenderUserName(message.getSenderUserName());
        response.setSenderUserId(message.getSenderUserId());
        response.setXyGoodsId(message.getXyGoodsId());
        response.setMessageTime(message.getMessageTime());
        response.setCreateTime(message.getCreateTime());
        response.setDirection(buyerUserId.equals(message.getSenderUserId()) ? "BUYER" : "SELLER");
        response.setRelatedOrderIds(resolveRelatedOrderIds(message,
                sessionOrders.getOrDefault(message.getSId(), List.of())));
        return response;
    }

    private List<String> resolveRelatedOrderIds(
            com.xianyusmart.entity.XianyuChatMessage message,
            List<com.xianyusmart.entity.XianyuGoodsOrder> sessionOrders) {
        if (sessionOrders.isEmpty()) {
            return List.of();
        }
        List<com.xianyusmart.entity.XianyuGoodsOrder> candidates = sessionOrders;
        if (message.getPnmId() != null && !message.getPnmId().isBlank()) {
            List<com.xianyusmart.entity.XianyuGoodsOrder> pnmMatches = sessionOrders.stream()
                    .filter(order -> message.getPnmId().equals(order.getPnmId()))
                    .toList();
            if (!pnmMatches.isEmpty()) {
                candidates = pnmMatches;
            }
        }
        if (candidates == sessionOrders && message.getXyGoodsId() != null && !message.getXyGoodsId().isBlank()) {
            List<com.xianyusmart.entity.XianyuGoodsOrder> goodsMatches = sessionOrders.stream()
                    .filter(order -> message.getXyGoodsId().equals(order.getXyGoodsId()))
                    .toList();
            if (!goodsMatches.isEmpty()) {
                candidates = goodsMatches;
            }
        }
        long messageTimestamp = message.getMessageTime() == null
                ? (message.getCreateTime() == null ? 0L
                    : message.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                : message.getMessageTime();
        // 同会话多订单时按消息标识、商品和时间选出最相关订单，避免整段会话重复归到每一单。
        com.xianyusmart.entity.XianyuGoodsOrder relatedOrder = candidates.stream()
                .min(java.util.Comparator.comparingLong(order ->
                        timeDistance(messageTimestamp, orderTimestamp(order))))
                .orElse(candidates.getFirst());
        return relatedOrder.getOrderId() == null ? List.of() : List.of(relatedOrder.getOrderId());
    }

    private long orderTimestamp(com.xianyusmart.entity.XianyuGoodsOrder order) {
        String value = order.getOrderCreateTime() == null || order.getOrderCreateTime().isBlank()
                ? order.getCreateTime()
                : order.getOrderCreateTime();
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(value.trim().replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long timeDistance(long messageTimestamp, long orderTimestamp) {
        if (messageTimestamp <= 0 || orderTimestamp <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.abs(messageTimestamp - orderTimestamp);
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
