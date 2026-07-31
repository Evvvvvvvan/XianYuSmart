package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.RiskControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 风控检测服务实现
 * 参考Python代码的风控处理逻辑
 */
@Slf4j
@Service
public class RiskControlServiceImpl implements RiskControlService {

    private static final long RATE_WINDOW_MS = 60_000L;
    private static final long CIRCUIT_WINDOW_MS = 600_000L;
    private static final Set<String> GUARDED_WRITE_APIS = Set.of(
            "mtop.idle.pc.idleitem.publish",
            "mtop.taobao.idle.item.downshelf",
            "com.taobao.idle.item.delete",
            "mtop.taobao.idle.item.polish",
            "mtop.taobao.idle.merchant.rate.create",
            "mtop.idle.groupon.activity.seller.freeshipping",
            "mtop.taobao.idle.logistics.merchant.consign.dummy",
            "mtop.taobao.idle.logistic.consign.dummy"
    );

    /**
     * 风控错误码
     * 参考Python: RGV587_ERROR
     */
    private static final String RISK_CONTROL_ERROR = "RGV587_ERROR";
    
    /**
     * 限流错误提示
     * 参考Python: 被挤爆啦
     */
    private static final String RATE_LIMIT_ERROR = "被挤爆啦";
    
    /**
     * Token失效错误码
     */
    private static final String TOKEN_EXPIRED_ERROR = "TOKEN_EXPIRED";
    
    /**
     * 成功标识
     * 参考Python: SUCCESS::调用成功
     */
    private static final String SUCCESS_FLAG = "SUCCESS::调用成功";

    private final ObjectMapper objectMapper;
    private final Path stateFile;
    private final Clock clock;
    private final OperationLogService operationLogService;
    private final Map<String, Long> rateLimits = new HashMap<>();
    private final Map<Long, CircuitEntry> circuits = new HashMap<>();

    @Autowired
    public RiskControlServiceImpl(ObjectMapper objectMapper,
                                  @Value("${app.risk-guard.state-file:${user.dir}/data/platform-risk-guard.json}")
                                  String stateFile,
                                  OperationLogService operationLogService) {
        this(objectMapper, Path.of(stateFile), Clock.systemUTC(), operationLogService);
    }

    RiskControlServiceImpl(ObjectMapper objectMapper, Path stateFile, Clock clock,
                           OperationLogService operationLogService) {
        this.objectMapper = objectMapper;
        this.stateFile = stateFile;
        this.clock = clock;
        this.operationLogService = operationLogService;
        loadState();
    }

    @Override
    public boolean detectRiskControl(Map<String, Object> response) {
        if (response == null) {
            return false;
        }

        return detectRiskReason(response) != null;
    }
    
    @Override
    public RiskControlResult handleRiskControl(Long accountId, Map<String, Object> response) {
        if (response == null) {
            return RiskControlResult.NORMAL;
        }
        
        // 检查Token是否失效
        if (isTokenExpired(response)) {
            log.error("【账号{}】❌ Token失效，需要刷新Token", accountId);
            return RiskControlResult.TOKEN_EXPIRED;
        }
        
        // 检查是否触发风控
        if (detectRiskControl(response)) {
            Object retObj = response.get("ret");
            String errorMsg = retObj != null ? retObj.toString() : "未知错误";
            
            log.error("【账号{}】❌ 触发风控: {}", accountId, errorMsg);
            log.error("【账号{}】🔴 系统目前无法自动解决，请进入闲鱼网页版-点击消息-过滑块-复制最新的Cookie", accountId);
            recordResponse(accountId, response);
            
            // 检查是否是限流
            if (errorMsg.contains(RATE_LIMIT_ERROR)) {
                log.warn("【账号{}】触发限流，建议稍后重试", accountId);
                return RiskControlResult.RATE_LIMITED;
            }
            
            return RiskControlResult.RISK_CONTROL_DETECTED;
        }
        
        return RiskControlResult.NORMAL;
    }
    
    @Override
    public boolean isTokenExpired(Map<String, Object> response) {
        if (response == null) {
            return false;
        }
        
        // 检查code字段
        Object codeObj = response.get("code");
        if (codeObj != null) {
            int code = 0;
            if (codeObj instanceof Number) {
                code = ((Number) codeObj).intValue();
            } else if (codeObj instanceof String) {
                try {
                    code = Integer.parseInt((String) codeObj);
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
            
            // 401表示Token失效
            if (code == 401) {
                return true;
            }
        }
        
        // 检查ret字段
        Object retObj = response.get("ret");
        if (retObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> retList = (List<String>) retObj;
            String errorMsg = retList.toString();
            
            // 检查Token失效错误
            if (errorMsg.contains(TOKEN_EXPIRED_ERROR) || 
                errorMsg.contains("TOKEN_FAIL") ||
                errorMsg.contains("登录过期")) {
                return true;
            }
        }
        
        return false;
    }

    @Override
    public synchronized GuardDecision tryAcquire(Long accountId, WriteOperation operation) {
        if (accountId == null || operation == null) {
            return new GuardDecision(false, GuardState.RATE_WAIT, 60,
                    now() + RATE_WINDOW_MS, "账号和操作类型不能为空", operation);
        }

        GuardDecision circuit = currentCircuit(accountId, operation);
        if (!circuit.allowed()) {
            return circuit;
        }

        long currentTime = now();
        String key = rateKey(accountId, operation);
        Long lastAllowedAt = rateLimits.get(key);
        if (lastAllowedAt != null && currentTime - lastAllowedAt < RATE_WINDOW_MS) {
            long retryAt = lastAllowedAt + RATE_WINDOW_MS;
            return new GuardDecision(false, GuardState.RATE_WAIT,
                    remainingSeconds(retryAt), retryAt, "写操作等待中", operation);
        }

        rateLimits.put(key, currentTime);
        persistState();
        return new GuardDecision(true, GuardState.NORMAL, 0, 0, null, operation);
    }

    @Override
    public synchronized GuardDecision checkApiWrite(Long accountId, String apiName) {
        if (apiName == null || !GUARDED_WRITE_APIS.contains(apiName)) {
            return new GuardDecision(true, GuardState.NORMAL, 0, 0, null, null);
        }
        return currentCircuit(accountId, null);
    }

    @Override
    public synchronized void recordResponse(Long accountId, Map<String, Object> response) {
        String reason = detectRiskReason(response);
        if (accountId == null || reason == null) {
            return;
        }

        long retryAt = now() + CIRCUIT_WINDOW_MS;
        CircuitEntry current = circuits.get(accountId);
        if (current != null && current.retryAt() > retryAt) {
            retryAt = current.retryAt();
        }
        circuits.put(accountId, new CircuitEntry(retryAt, reason));
        persistState();
        log.warn("【账号{}】平台写操作熔断已开启: reason={}, remainingSeconds={}",
                accountId, reason, remainingSeconds(retryAt));
        logGuardEvent(accountId, "平台风控熔断已开启", OperationConstants.Status.FAIL, reason);
    }

    @Override
    public synchronized void clearCircuit(Long accountId) {
        if (accountId == null || circuits.remove(accountId) == null) {
            return;
        }
        persistState();
        log.info("【账号{}】完整凭证已生效，平台写操作熔断已解除", accountId);
        logGuardEvent(accountId, "完整凭证生效，平台风控熔断已解除",
                OperationConstants.Status.SUCCESS, null);
    }

    @Override
    public synchronized GuardStatus getStatus(Long accountId) {
        GuardDecision circuit = currentCircuit(accountId, null);
        if (!circuit.allowed()) {
            return new GuardStatus(circuit.state(), circuit.remainingSeconds(), circuit.retryAt(),
                    circuit.reason(), null);
        }

        long currentTime = now();
        WriteOperation latestOperation = null;
        long latestRetryAt = 0;
        for (WriteOperation operation : WriteOperation.values()) {
            Long lastAllowedAt = rateLimits.get(rateKey(accountId, operation));
            if (lastAllowedAt == null) {
                continue;
            }
            long retryAt = lastAllowedAt + RATE_WINDOW_MS;
            if (retryAt > currentTime && retryAt > latestRetryAt) {
                latestRetryAt = retryAt;
                latestOperation = operation;
            }
        }
        if (latestOperation != null) {
            return new GuardStatus(GuardState.RATE_WAIT, remainingSeconds(latestRetryAt),
                    latestRetryAt, "写操作等待中", latestOperation);
        }
        return new GuardStatus(GuardState.NORMAL, 0, 0, null, null);
    }

    private GuardDecision currentCircuit(Long accountId, WriteOperation operation) {
        if (accountId == null) {
            return new GuardDecision(true, GuardState.NORMAL, 0, 0, null, operation);
        }
        CircuitEntry circuit = circuits.get(accountId);
        if (circuit == null) {
            return new GuardDecision(true, GuardState.NORMAL, 0, 0, null, operation);
        }
        if (circuit.retryAt() <= now()) {
            circuits.remove(accountId);
            persistState();
            return new GuardDecision(true, GuardState.NORMAL, 0, 0, null, operation);
        }
        return new GuardDecision(false, GuardState.CIRCUIT_OPEN,
                remainingSeconds(circuit.retryAt()), circuit.retryAt(), circuit.reason(), operation);
    }

    private String detectRiskReason(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        String text = String.valueOf(response.get("ret")) + " "
                + String.valueOf(response.get("code")) + " "
                + String.valueOf(response.get("msg")) + " "
                + String.valueOf(response.get("message"));
        String normalized = text.toLowerCase();
        if (normalized.contains("rgv587")) {
            return "RGV587";
        }
        if (normalized.contains("fail_sys_user_validate")) {
            return "USER_VALIDATE";
        }
        if (normalized.contains("fail_sys_illegal_access")) {
            return "ILLEGAL_ACCESS";
        }
        if (normalized.contains("wua_is_machine")) {
            return "WUA_MACHINE";
        }
        if (text.contains(RATE_LIMIT_ERROR) || text.contains("被挤爆")) {
            return "REQUEST_BUSY";
        }
        if (text.contains("哎哟喂")) {
            return "PLATFORM_REJECTED";
        }
        if (normalized.contains("punish")) {
            return "PUNISH";
        }
        if (normalized.contains("captcha")) {
            return "CAPTCHA";
        }
        return null;
    }

    private String rateKey(Long accountId, WriteOperation operation) {
        return accountId + ":" + operation.name();
    }

    private long now() {
        return clock.millis();
    }

    private long remainingSeconds(long retryAt) {
        return Math.max(0, (retryAt - now() + 999) / 1000);
    }

    private void loadState() {
        synchronized (this) {
            if (!Files.exists(stateFile)) {
                return;
            }
            try {
                PersistedState state = objectMapper.readValue(stateFile.toFile(), PersistedState.class);
                if (state.rateLimits() != null) {
                    rateLimits.putAll(state.rateLimits());
                    rateLimits.entrySet().removeIf(entry -> entry.getValue() + RATE_WINDOW_MS <= now());
                }
                if (state.circuits() != null) {
                    circuits.putAll(state.circuits());
                    circuits.entrySet().removeIf(entry -> entry.getValue().retryAt() <= now());
                }
            } catch (Exception e) {
                rateLimits.clear();
                circuits.clear();
                log.warn("平台风控护栏状态文件无效，已忽略: path={}", stateFile);
            }
        }
    }

    private void persistState() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            objectMapper.writeValue(tempFile.toFile(),
                    new PersistedState(new HashMap<>(rateLimits), new HashMap<>(circuits)));
            try {
                Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("平台风控护栏状态保存失败: path={}, type={}",
                    stateFile, e.getClass().getSimpleName());
        }
    }

    private void logGuardEvent(Long accountId, String description, int status, String errorMessage) {
        if (operationLogService == null) {
            return;
        }
        operationLogService.log(accountId, OperationConstants.Type.UPDATE,
                OperationConstants.Module.RISK_CONTROL, description, status,
                OperationConstants.TargetType.ACCOUNT, String.valueOf(accountId),
                null, null, errorMessage, null);
    }

    private record CircuitEntry(long retryAt, String reason) {
    }

    private record PersistedState(Map<String, Long> rateLimits, Map<Long, CircuitEntry> circuits) {
    }
}
