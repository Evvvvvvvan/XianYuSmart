package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;



/**
 * 闲鱼账号实体类
 */
@Data
@TableName("xianyu_account")
public class XianyuAccount {
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;
    
    /**
     * 闲鱼账号备注
     */
    private String accountNote;
    
    /**
     * UNB标识
     */
    private String unb;
    
    /**
     * 设备ID（UUID格式-用户ID，用于WebSocket连接）
     * 格式: XXXXXXXX-XXXX-4XXX-XXXX-XXXXXXXXXXXX-用户ID
     * 例如: ED4CBA2C-5DA0-4154-A902-BF5CB52409E2-3888777108
     */
    private String deviceId;
    
    /**
     * 账号状态 1:正常 -2:需要验证（滑块/手机号/凭证失效，异常待处理）
     * 历史数据中可能存在 -1，仅作兼容读取，新写入统一为 -2。
     */
    private Integer status;

    /**
     * 消息同步游标，用于断线后从最后确认位置继续接收。
     */
    private Long websocketSyncPts;

    private Long websocketSyncSeq;

    private Long websocketSyncTimestamp;
    
    /**
     * 创建时间（SQLite存储为TEXT）
     */
    private String createdTime;
    
    /**
     * 更新时间（SQLite存储为TEXT）
     */
    private String updatedTime;
}
