package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.config.rag.AIEndpointResolver;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.service.SysSettingService;
import com.xianyusmart.service.EmailNotifyService;
import com.xianyusmart.service.bo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统配置控制器
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/setting")
public class SysSettingController {

    @Autowired
    private SysSettingService sysSettingService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    @Autowired(required = false)
    private EmailNotifyService emailNotifyService;

    /**
     * 获取配置
     */
    @PostMapping("/get")
    public ResultObject<GetSettingRespDTO> getSetting(@RequestBody GetSettingReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getSettingKey() == null || reqDTO.getSettingKey().trim().isEmpty()) {
                return ResultObject.validateFailed("配置键不能为空");
            }

            GetSettingReqBO reqBO = new GetSettingReqBO();
            reqBO.setSettingKey(reqDTO.getSettingKey());

            GetSettingRespBO respBO = sysSettingService.getSetting(reqBO);

            GetSettingRespDTO respDTO = new GetSettingRespDTO();
            if (respBO != null) {
                respDTO.setSettingKey(respBO.getSettingKey());
                respDTO.setSettingValue(respBO.getSettingValue());
                respDTO.setSettingDesc(respBO.getSettingDesc());
            } else {
                respDTO.setSettingKey(reqDTO.getSettingKey());
                respDTO.setSettingValue(null);
                respDTO.setSettingDesc(null);
            }
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取配置失败", e);
            return ResultObject.failed("获取配置失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有配置
     */
    @PostMapping("/list")
    public ResultObject<List<GetSettingRespDTO>> getAllSettings() {
        try {
            List<GetSettingRespBO> respBOList = sysSettingService.getAllSettings();
            List<GetSettingRespDTO> result = new ArrayList<>();

            for (GetSettingRespBO respBO : respBOList) {
                GetSettingRespDTO respDTO = new GetSettingRespDTO();
                respDTO.setSettingKey(respBO.getSettingKey());
                respDTO.setSettingValue(respBO.getSettingValue());
                respDTO.setSettingDesc(respBO.getSettingDesc());
                result.add(respDTO);
            }

            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("获取所有配置失败", e);
            return ResultObject.failed("获取所有配置失败: " + e.getMessage());
        }
    }

    /**
     * 保存配置
     */
    @PostMapping("/save")
    public ResultObject<?> saveSetting(@RequestBody SaveSettingReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getSettingKey() == null || reqDTO.getSettingKey().trim().isEmpty()) {
                return ResultObject.validateFailed("配置键不能为空");
            }
            if (isAIBaseUrlSetting(reqDTO.getSettingKey()) && !isBlank(reqDTO.getSettingValue())) {
                AIEndpointResolver.validateBaseUrl(reqDTO.getSettingValue());
            }

            SaveSettingReqBO reqBO = new SaveSettingReqBO();
            reqBO.setSettingKey(reqDTO.getSettingKey());
            reqBO.setSettingValue(reqDTO.getSettingValue());
            reqBO.setSettingDesc(reqDTO.getSettingDesc());

            sysSettingService.saveSetting(reqBO);
            return ResultObject.success(null);
        } catch (IllegalArgumentException e) {
            return ResultObject.validateFailed(e.getMessage());
        } catch (Exception e) {
            log.error("保存配置失败", e);
            return ResultObject.failed("保存配置失败: " + e.getMessage());
        }
    }

    /**
     * 删除配置
     */
    @PostMapping("/delete")
    public ResultObject<?> deleteSetting(@RequestBody GetSettingReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getSettingKey() == null || reqDTO.getSettingKey().trim().isEmpty()) {
                return ResultObject.validateFailed("配置键不能为空");
            }

            sysSettingService.deleteSetting(reqDTO.getSettingKey());
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("删除配置失败", e);
            return ResultObject.failed("删除配置失败: " + e.getMessage());
        }
    }

    /**
     * 使用当前表单配置测试AI连接，不写入系统配置。
     */
    @PostMapping("/ai/test")
    public ResultObject<DynamicAIChatClientManager.ConnectionTestResult> testAIConnection(
            @RequestBody AIConnectionTestReqDTO reqDTO) {
        if (reqDTO == null || isBlank(reqDTO.getApiKey()) || isBlank(reqDTO.getBaseUrl())
                || isBlank(reqDTO.getModel()) || isBlank(reqDTO.getMessage())) {
            return ResultObject.validateFailed("API Key、Base URL、模型和测试内容不能为空");
        }
        DynamicAIChatClientManager.ChatConfig config = new DynamicAIChatClientManager.ChatConfig(
                reqDTO.getProvider(), reqDTO.getProtocol(), reqDTO.getCustomName(), reqDTO.getApiKey(),
                reqDTO.getBaseUrl(), reqDTO.getModel());
        return ResultObject.success(dynamicAIChatClientManager.testConnection(config, reqDTO.getMessage()));
    }

    /**
     * 测试邮箱配置
     */
    @PostMapping("/testEmail")
    public ResultObject<?> testEmail() {
        try {
            if (emailNotifyService == null) {
                return ResultObject.failed("邮件服务未初始化");
            }
            if (!emailNotifyService.isEmailConfigured()) {
                return ResultObject.failed("邮箱配置不完整，请先配置SMTP信息");
            }
            String error = emailNotifyService.sendTestEmail();
            if (error == null) {
                return ResultObject.success("测试邮件发送成功，请检查收件箱");
            } else {
                return ResultObject.failed(error);
            }
        } catch (Exception e) {
            log.error("测试邮箱失败", e);
            return ResultObject.failed("测试邮箱失败: " + e.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isAIBaseUrlSetting(String settingKey) {
        String normalizedKey = settingKey.trim();
        return "ai_base_url".equals(normalizedKey)
                || "ai_embedding_base_url".equals(normalizedKey)
                || "ai_image_base_url".equals(normalizedKey);
    }

    public static class AIConnectionTestReqDTO {
        private String provider;
        private String protocol;
        private String customName;
        private String apiKey;
        private String baseUrl;
        private String model;
        private String message;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getCustomName() { return customName; }
        public void setCustomName(String customName) { this.customName = customName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
