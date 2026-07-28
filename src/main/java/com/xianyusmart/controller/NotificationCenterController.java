package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.NotificationChannelReqDTO;
import com.xianyusmart.controller.dto.NotificationChannelRespDTO;
import com.xianyusmart.entity.XianyuNotificationLog;
import com.xianyusmart.service.NotificationCenterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通知中心接口
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationCenterController {

    private final NotificationCenterService notificationCenterService;

    public NotificationCenterController(NotificationCenterService notificationCenterService) {
        this.notificationCenterService = notificationCenterService;
    }

    @GetMapping("/channels")
    public ResultObject<List<NotificationChannelRespDTO>> listChannels() {
        return ResultObject.success(notificationCenterService.listChannels());
    }

    @PostMapping("/channels")
    public ResultObject<NotificationChannelRespDTO> saveChannel(
            @Valid @RequestBody NotificationChannelReqDTO request) {
        try {
            return ResultObject.success(notificationCenterService.saveChannel(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @DeleteMapping("/channels/{id}")
    public ResultObject<Void> deleteChannel(@PathVariable Long id) {
        try {
            notificationCenterService.deleteChannel(id);
            return ResultObject.success(null);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/channels/{id}/test")
    public ResultObject<Map<String, Object>> testChannel(@PathVariable Long id) {
        try {
            return ResultObject.success(notificationCenterService.testChannel(id));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/logs")
    public ResultObject<List<XianyuNotificationLog>> listLogs(
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(notificationCenterService.listLogs(limit));
    }
}
