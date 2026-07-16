package com.xianyusmart.service.reply;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuGoodsAutoReplyRecord;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.service.AutoReplyDelayService;
import com.xianyusmart.service.AutoReplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 自动回复延时调度服务
 *
 * <p>核心职责：管理买家消息的延时任务，到期后触发AI自动回复。</p>
 *
 * <h3>延时机制：</h3>
 * <ol>
 *   <li>买家消息到来 → 检查人工接管 → 未接管则提交延时任务</li>
 *   <li>延时期间收到新消息 → 取消旧任务，追加消息，重新计时</li>
 *   <li>延时到期 → 再次检查人工接管 → 未接管则执行AI回复</li>
 * </ol>
 *
 * <h3>人工干预拦截（两道防线）：</h3>
 * <ul>
 *   <li>防线1：消息到来时立即检查 {@link HumanTakeoverManager#isTakenOver}，接管中则不提交任务</li>
 *   <li>防线2：延时任务到期时再次检查，防止并发竞态导致漏拦</li>
 * </ul>
 *
 * <h3>依赖组件：</h3>
 * <ul>
 *   <li>{@link HumanTakeoverManager} - 人工接管状态管理（内存Map + 过期清理）</li>
 *   <li>{@link ReplyConfigProvider} - 回复配置查询（延时秒数、干预开关、干预时长）</li>
 *   <li>{@link AutoReplyService} - AI自动回复执行</li>
 * </ul>
 */
@Slf4j
@Service
public class AutoReplyDelayServiceImpl implements AutoReplyDelayService {
    
    @Autowired
    private AutoReplyService autoReplyService;

    @Autowired
    private HumanTakeoverManager takeoverManager;

    @Autowired
    private ReplyConfigProvider configProvider;

    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String workerId = "reply-" + UUID.randomUUID().toString().substring(0, 8);
    
    /** 延时任务调度线程池 */
    @Autowired
    @Qualifier("autoReplyScheduler")
    private ScheduledExecutorService scheduler;
    
    /**
     * 待执行的延时任务映射
     * <ul>
     *   <li>Key: accountId_sId</li>
     *   <li>Value: ScheduledFuture（延时任务句柄）</li>
     * </ul>
     */
    private final Map<String, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();
    
    /**
     * 延时期间收集的买家消息列表
     * <ul>
     *   <li>Key: accountId_sId</li>
     *   <li>Value: 该会话在延时期间收到的所有买家消息</li>
     * </ul>
     */
    private final Map<String, List<ChatMessageData>> pendingMessages = new ConcurrentHashMap<>();
    
    @PreDestroy
    @Override
    public void shutdown() {
        log.info("关闭自动回复延时调度器...");
        pendingTasks.forEach((key, future) -> {
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        });
        pendingTasks.clear();
        pendingMessages.clear();
        log.info("自动回复延时调度器已关闭");
    }
    
    /**
     * 提交延时回复任务
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查人工接管 → 接管中则直接跳过</li>
     *   <li>取消该会话之前的延时任务</li>
     *   <li>追加当前消息到待处理列表</li>
     *   <li>提交新的延时任务（到期后执行AI回复）</li>
     * </ol>
     */
    @Override
    public void submitDelayTask(ChatMessageData messageData) {
        if (messageData == null || messageData.getSId() == null) {
            log.warn("消息数据无效，无法提交延时任务");
            return;
        }
        
        Long accountId = messageData.getXianyuAccountId();
        String sId = messageData.getSId();
        String taskKey = buildTaskKey(accountId, sId);
        
        // 防线1：消息到来时立即检查人工接管
        boolean isTakenOver = takeoverManager.isTakenOver(accountId, sId);
        log.info("【账号{}】提交延时任务前检查人工接管: sId={}, isTakenOver={}", accountId, sId, isTakenOver);
        
        if (isTakenOver) {
            log.info("【账号{}】会话已被人工接管，跳过自动回复: sId={}", accountId, sId);
            return;
        }

        int delaySeconds = configProvider.getDelaySeconds(accountId, messageData.getXyGoodsId());
        log.info("【账号{}】提交延时回复任务: sId={}, delay={}s", accountId, sId, delaySeconds);
        
        // 取消该会话之前的延时任务（买家连续发消息时重新计时）
        cancelDelayTask(accountId, sId);
        
        // 追加消息到待处理列表
        pendingMessages.compute(taskKey, (key, existingList) -> {
            if (existingList == null) existingList = new ArrayList<>();
            existingList.add(messageData);
            return existingList;
        });
        
        List<ChatMessageData> messageList = new ArrayList<>(pendingMessages.get(taskKey));
        Long recordId = persistTask(messageList, delaySeconds);

        // 提交新的延时任务
        ScheduledFuture<?> future = scheduler.schedule(
                () -> dispatchTask(recordId, messageList), delaySeconds, TimeUnit.SECONDS);
        
        pendingTasks.put(taskKey, future);
    }
    
    @Override
    public void cancelDelayTask(Long accountId, String sId) {
        if (accountId == null || sId == null) return;
        String taskKey = buildTaskKey(accountId, sId);
        ScheduledFuture<?> future = pendingTasks.remove(taskKey);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        autoReplyRecordMapper.cancelPendingBySession(accountId, sId);
    }
    
    @Override
    public int getPendingTaskCount() {
        return autoReplyRecordMapper.countPending();
    }

    /**
     * 记录卖家手动回复，触发人工接管
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查人工干预开关是否开启</li>
     *   <li>标记该会话为人工接管（持续N分钟）</li>
     *   <li>立即取消该会话的延时任务</li>
     *   <li>清除待处理消息</li>
     * </ol>
     */
    @Override
    public void recordSellerManualReply(Long accountId, String xyGoodsId, String sId) {
        if (accountId == null || sId == null) {
            log.warn("recordSellerManualReply参数无效: accountId={}, sId={}, xyGoodsId={}", accountId, sId, xyGoodsId);
            return;
        }

        boolean interventionEnabled = configProvider.isHumanInterventionEnabled(accountId, xyGoodsId);
        log.info("【账号{}】卖家手动回复，检查人工干预开关: sId={}, xyGoodsId={}, enabled={}", accountId, sId, xyGoodsId, interventionEnabled);

        if (!interventionEnabled) {
            log.info("【账号{}】人工干预未开启或xyGoodsId无效，跳过接管: sId={}, xyGoodsId={}", accountId, sId, xyGoodsId);
            return;
        }

        int minutes = configProvider.getInterventionMinutes(accountId, xyGoodsId);
        takeoverManager.takeover(accountId, xyGoodsId, sId, minutes);

        // 立即取消该会话的延时任务和待处理消息
        cancelDelayTask(accountId, sId);
        pendingMessages.remove(buildTaskKey(accountId, sId));

        log.info("【账号{}】卖家手动回复，人工接管: sId={}, xyGoodsId={}, {}分钟后恢复", accountId, sId, xyGoodsId, minutes);
    }
    
    private String buildTaskKey(Long accountId, String sId) {
        return accountId + "_" + sId;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void recoverDueTasks() {
        for (XianyuGoodsAutoReplyRecord record : autoReplyRecordMapper.findDue(20)) {
            try {
                List<ChatMessageData> messages = objectMapper.readValue(
                        record.getTriggerContext(), new TypeReference<List<ChatMessageData>>() {});
                dispatchTask(record.getId(), messages);
            } catch (Exception e) {
                log.error("恢复延时回复任务失败: recordId={}", record.getId(), e);
                autoReplyRecordMapper.updateStateAndContent(record.getId(), -1, null);
            }
        }
    }

    private Long persistTask(List<ChatMessageData> messages, int delaySeconds) {
        ChatMessageData lastMessage = messages.getLast();
        XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
        record.setXianyuAccountId(lastMessage.getXianyuAccountId());
        record.setXyGoodsId(lastMessage.getXyGoodsId());
        record.setSId(lastMessage.getSId());
        record.setPnmId(lastMessage.getPnmId());
        record.setBuyerUserId(lastMessage.getSenderUserId());
        record.setBuyerUserName(lastMessage.getSenderUserName());
        record.setBuyerMessage(messages.stream().map(ChatMessageData::getMsgContent)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n")));
        record.setTriggerContext(writeMessages(messages));
        record.setState(0);
        record.setScheduledTime(LocalDateTime.now().plusSeconds(delaySeconds));
        autoReplyRecordMapper.insert(record);
        return record.getId();
    }

    private String writeMessages(List<ChatMessageData> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            throw new IllegalStateException("延时回复上下文序列化失败", e);
        }
    }

    private void dispatchTask(Long recordId, List<ChatMessageData> messages) {
        if (recordId == null || messages == null || messages.isEmpty()) {
            return;
        }
        ChatMessageData lastMessage = messages.getLast();
        String taskKey = buildTaskKey(lastMessage.getXianyuAccountId(), lastMessage.getSId());
        pendingTasks.remove(taskKey);
        pendingMessages.remove(taskKey);
        if (autoReplyRecordMapper.claim(recordId, workerId, 120) == 0) {
            return;
        }
        taskExecutor.execute(() -> executeClaimedTask(recordId, messages));
    }

    private void executeClaimedTask(Long recordId, List<ChatMessageData> messages) {
        ChatMessageData lastMessage = messages.getLast();
        Long accountId = lastMessage.getXianyuAccountId();
        String sId = lastMessage.getSId();
        try {
            // 后台回复按账号恢复租户上下文，确保AI配置和业务数据保持隔离。
            var account = accountMapper.selectById(accountId);
            if (account == null) {
                throw new IllegalStateException("闲鱼账号不存在");
            }
            TenantContext.set(account.getTenantId());
            if (takeoverManager.isTakenOver(accountId, sId)) {
                autoReplyRecordMapper.cancelById(recordId);
                return;
            }
            autoReplyService.executeAutoReply(messages, recordId);
            XianyuGoodsAutoReplyRecord result = autoReplyRecordMapper.selectById(recordId);
            if (result != null && Integer.valueOf(2).equals(result.getState())) {
                autoReplyRecordMapper.updateStateAndContent(recordId, -1, null);
            }
        } catch (Exception e) {
            log.error("【账号{}】执行持久化延时回复异常: sId={}", accountId, sId, e);
            autoReplyRecordMapper.updateStateAndContent(recordId, -1, null);
        } finally {
            TenantContext.clear();
        }
    }
}
