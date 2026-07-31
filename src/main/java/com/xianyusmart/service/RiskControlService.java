package com.xianyusmart.service;

import java.util.Map;

/**
 * 风控检测服务
 * 参考Python代码的风控处理逻辑
 */
public interface RiskControlService {
    
    /**
     * 检测API响应是否触发风控
     * 
     * @param response API响应数据
     * @return true=触发风控，false=正常
     */
    boolean detectRiskControl(Map<String, Object> response);
    
    /**
     * 处理风控情况
     * 
     * @param accountId 账号ID
     * @param response API响应数据
     * @return 处理结果
     */
    RiskControlResult handleRiskControl(Long accountId, Map<String, Object> response);
    
    /**
     * 检测Token是否失效
     * 
     * @param response API响应数据
     * @return true=Token失效，false=正常
     */
    boolean isTokenExpired(Map<String, Object> response);

    /**
     * 获取一次非时效写操作额度
     *
     * @param accountId 账号ID
     * @param operation 逻辑业务类型
     * @return 护栏判断
     */
    GuardDecision tryAcquire(Long accountId, WriteOperation operation);

    /**
     * 检查平台写接口是否处于熔断期
     *
     * @param accountId 账号ID
     * @param apiName API名称
     * @return 护栏判断
     */
    GuardDecision checkApiWrite(Long accountId, String apiName);

    /**
     * 记录平台响应中的风控信号
     *
     * @param accountId 账号ID
     * @param response API响应数据
     */
    void recordResponse(Long accountId, Map<String, Object> response);

    /**
     * 完整凭证更新成功后解除熔断
     *
     * @param accountId 账号ID
     */
    void clearCircuit(Long accountId);

    /**
     * 获取账号当前护栏状态
     *
     * @param accountId 账号ID
     * @return 护栏状态
     */
    GuardStatus getStatus(Long accountId);

    /**
     * 非时效写操作分类
     */
    enum WriteOperation {
        ITEM_PUBLISH,
        ITEM_DELETE,
        ITEM_STATUS,
        ITEM_POLISH,
        ORDER_RATE
    }

    /**
     * 护栏状态
     */
    enum GuardState {
        NORMAL,
        RATE_WAIT,
        CIRCUIT_OPEN,
        RECOVERING
    }

    /**
     * 单次护栏判断
     */
    record GuardDecision(boolean allowed, GuardState state, long remainingSeconds,
                         long retryAt, String reason, WriteOperation operation) {
    }

    /**
     * 账号护栏状态视图
     */
    record GuardStatus(GuardState state, long remainingSeconds, long retryAt,
                       String reason, WriteOperation operation) {
    }
    
    /**
     * 风控处理结果
     */
    enum RiskControlResult {
        /**
         * 正常，无风控
         */
        NORMAL,
        
        /**
         * 触发风控，需要更新Cookie
         */
        RISK_CONTROL_DETECTED,
        
        /**
         * Token失效，需要刷新Token
         */
        TOKEN_EXPIRED,
        
        /**
         * 限流，需要等待
         */
        RATE_LIMITED
    }
}
