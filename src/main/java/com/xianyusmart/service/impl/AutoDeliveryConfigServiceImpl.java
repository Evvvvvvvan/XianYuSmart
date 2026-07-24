package com.xianyusmart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.controller.dto.AutoDeliveryConfigReqDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigRespDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.xianyusmart.service.AutoDeliveryConfigService;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.FixedDeliveryTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AutoDeliveryConfigServiceImpl implements AutoDeliveryConfigService {
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private BuyerMessageService buyerMessageService;

    @Autowired
    private FixedDeliveryTemplateService fixedDeliveryTemplateService;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO) {
        try {
            if (reqDTO.getDeliveryMode() == null) {
                reqDTO.setDeliveryMode(1);
            }
            // 两种发货模式只保存当前模式所需字段，避免历史关联形成隐性组合配置。
            normalizeExclusiveDeliveryFields(reqDTO);
            validateResourceOwnership(reqDTO);
            normalizeDeliveryChannels(reqDTO);
            validateDeliveryContent(reqDTO);
            String deliveryMessageTemplate = reqDTO.getDeliveryMode() == 2
                    ? buyerMessageService.normalizeDeliveryMessageTemplate(reqDTO.getDeliveryMessageTemplate())
                    : null;
            String receiptFollowUpMessages = buyerMessageService.normalizeReceiptFollowUpMessages(
                    reqDTO.getReceiptFollowUpMessages());
            int receiptFollowUpIntervalSeconds = buyerMessageService.normalizeReceiptFollowUpInterval(
                    reqDTO.getReceiptFollowUpIntervalSeconds());
            String skuId = reqDTO.getSkuId();
            XianyuGoodsAutoDeliveryConfig existingConfig = null;
            if (skuId != null && !skuId.isEmpty()) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdAndSkuId(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
            }
            if (existingConfig == null) {
                existingConfig = autoDeliveryConfigMapper
                        .findByAccountIdAndGoodsIdNoSku(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                if (existingConfig != null && skuId != null && !skuId.isEmpty()) {
                    existingConfig = null;
                }
            }
            
            XianyuGoodsAutoDeliveryConfig config;
            if (existingConfig != null) {
                config = existingConfig;
                config.setDeliveryMode(reqDTO.getDeliveryMode());
                config.setFixedTemplateId(reqDTO.getFixedTemplateId());
                config.setSkuId(reqDTO.getSkuId());
                config.setSkuName(reqDTO.getSkuName());
                config.setAutoDeliveryContent(reqDTO.getAutoDeliveryContent());
                config.setKamiConfigIds(reqDTO.getKamiConfigIds());
                config.setKamiDeliveryTemplate(null);
                config.setDeliveryMessageTemplate(deliveryMessageTemplate);
                config.setVoucherDeliveryEnabled(reqDTO.getVoucherDeliveryEnabled());
                config.setChatDeliveryEnabled(reqDTO.getChatDeliveryEnabled());
                config.setReceiptFollowUpMessages(receiptFollowUpMessages);
                config.setReceiptFollowUpIntervalSeconds(receiptFollowUpIntervalSeconds);
                config.setAutoDeliveryImageUrl(reqDTO.getAutoDeliveryImageUrl());
                config.setXianyuGoodsId(reqDTO.getXianyuGoodsId());
                config.setAutoConfirmShipment(1);
                
                autoDeliveryConfigMapper.updateById(config);
                log.info("更新自动发货配置成功，ID: {}", config.getId());
            } else {
                config = new XianyuGoodsAutoDeliveryConfig();
                BeanUtils.copyProperties(reqDTO, config);
                if (config.getSkuId() == null) {
                    config.setSkuId(null);
                }
                config.setKamiDeliveryTemplate(null);
                config.setDeliveryMessageTemplate(deliveryMessageTemplate);
                config.setVoucherDeliveryEnabled(reqDTO.getVoucherDeliveryEnabled());
                config.setChatDeliveryEnabled(reqDTO.getChatDeliveryEnabled());
                config.setReceiptFollowUpMessages(receiptFollowUpMessages);
                config.setReceiptFollowUpIntervalSeconds(receiptFollowUpIntervalSeconds);
                config.setAutoConfirmShipment(1);
                
                autoDeliveryConfigMapper.insert(config);
                log.info("创建自动发货配置成功，ID: {}", config.getId());
            }
            
            AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
            BeanUtils.copyProperties(config, respDTO);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("保存自动发货配置失败", e);
            return ResultObject.failed("保存自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO) {
        try {
            log.info("开始查询自动发货配置: xianyuAccountId={}, xyGoodsId={}, skuId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getSkuId());
            
            XianyuGoodsAutoDeliveryConfig config = null;
            
            if (reqDTO.getXyGoodsId() != null && !reqDTO.getXyGoodsId().trim().isEmpty()) {
                String skuId = reqDTO.getSkuId();
                if (skuId != null && !skuId.isEmpty()) {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), skuId);
                }
                if (config == null) {
                    config = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                            reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
                }
            } else {
                List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                        .findByAccountId(reqDTO.getXianyuAccountId());
                config = configs.isEmpty() ? null : configs.get(0);
            }
            
            if (config == null) {
                return ResultObject.success(null);
            }
            
            AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
            BeanUtils.copyProperties(config, respDTO);
            
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("查询自动发货配置失败", e);
            return ResultObject.failed("查询自动发货配置失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountIdAndGoodsId(xianyuAccountId, xyGoodsId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
                        BeanUtils.copyProperties(config, respDTO);
                        return respDTO;
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询商品自动发货配置列表失败", e);
            return ResultObject.failed("查询商品自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper
                    .findByAccountId(xianyuAccountId);
            
            List<AutoDeliveryConfigRespDTO> respDTOs = configs.stream()
                    .map(config -> {
                        AutoDeliveryConfigRespDTO respDTO = new AutoDeliveryConfigRespDTO();
                        BeanUtils.copyProperties(config, respDTO);
                        return respDTO;
                    })
                    .collect(Collectors.toList());
            
            return ResultObject.success(respDTOs);
        } catch (Exception e) {
            log.error("查询账号自动发货配置列表失败", e);
            return ResultObject.failed("查询账号自动发货配置列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId) {
        try {
            LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, xianyuAccountId)
                   .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, xyGoodsId);
            
            int deletedCount = autoDeliveryConfigMapper.delete(wrapper);
            
            if (deletedCount > 0) {
                log.info("删除自动发货配置成功，账号ID: {}, 商品ID: {}", xianyuAccountId, xyGoodsId);
                return ResultObject.success(null);
            } else {
                return ResultObject.failed("未找到对应的自动发货配置");
            }
        } catch (Exception e) {
            log.error("删除自动发货配置失败", e);
            return ResultObject.failed("删除自动发货配置失败: " + e.getMessage());
        }
    }

    private void validateDeliveryContent(AutoDeliveryConfigReqDTO reqDTO) {
        int deliveryMode = reqDTO.getDeliveryMode() == null ? 1 : reqDTO.getDeliveryMode();
        if (deliveryMode != 1 && deliveryMode != 2) {
            throw new IllegalArgumentException("发货类型只能选择固定内容或卡密");
        }
        boolean voucherEnabled = Integer.valueOf(1).equals(reqDTO.getVoucherDeliveryEnabled());
        boolean chatEnabled = Integer.valueOf(1).equals(reqDTO.getChatDeliveryEnabled());
        if (!voucherEnabled && !chatEnabled) {
            throw new IllegalArgumentException("发货凭证和买家私聊至少开启一个");
        }
        if (deliveryMode == 1) {
            if (fixedDeliveryTemplateService.findOwnedTemplate(
                    reqDTO.getXianyuAccountId(), reqDTO.getFixedTemplateId()) == null) {
                throw new IllegalArgumentException("固定内容发货必须选择有效模板");
            }
        }
        if (deliveryMode == 2 && (reqDTO.getKamiConfigIds() == null || reqDTO.getKamiConfigIds().isBlank())) {
            throw new IllegalArgumentException("卡密发货必须绑定卡密配置");
        }
    }

    private void normalizeDeliveryChannels(AutoDeliveryConfigReqDTO reqDTO) {
        int voucherEnabled = reqDTO.getVoucherDeliveryEnabled() == null
                ? 1 : reqDTO.getVoucherDeliveryEnabled();
        int chatEnabled = reqDTO.getChatDeliveryEnabled() == null
                ? 1 : reqDTO.getChatDeliveryEnabled();
        if ((voucherEnabled != 0 && voucherEnabled != 1) || (chatEnabled != 0 && chatEnabled != 1)) {
            throw new IllegalArgumentException("发送渠道开关参数无效");
        }
        reqDTO.setVoucherDeliveryEnabled(voucherEnabled);
        reqDTO.setChatDeliveryEnabled(chatEnabled);
    }

    private void normalizeExclusiveDeliveryFields(AutoDeliveryConfigReqDTO reqDTO) {
        if (reqDTO.getDeliveryMode() == 1) {
            reqDTO.setKamiConfigIds(null);
            reqDTO.setKamiDeliveryTemplate(null);
            reqDTO.setDeliveryMessageTemplate(null);
        } else if (reqDTO.getDeliveryMode() == 2) {
            reqDTO.setFixedTemplateId(null);
            reqDTO.setKamiDeliveryTemplate(null);
        }
        reqDTO.setAutoDeliveryContent(null);
    }

    private void validateResourceOwnership(AutoDeliveryConfigReqDTO reqDTO) {
        Long accountId = reqDTO.getXianyuAccountId();
        if (accountId == null || accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("闲鱼账号不存在或无权访问");
        }
        LambdaQueryWrapper<XianyuGoodsInfo> goodsQuery = new LambdaQueryWrapper<>();
        goodsQuery.eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
                .eq(XianyuGoodsInfo::getXyGoodId, reqDTO.getXyGoodsId());
        if (goodsInfoMapper.selectCount(goodsQuery) == 0) {
            throw new IllegalArgumentException("商品不存在或不属于当前账号");
        }
        if (reqDTO.getDeliveryMode() == null || reqDTO.getDeliveryMode() != 2
                || reqDTO.getKamiConfigIds() == null || reqDTO.getKamiConfigIds().isBlank()) {
            return;
        }
        for (String configIdText : reqDTO.getKamiConfigIds().split(",")) {
            try {
                XianyuKamiConfig kamiConfig =
                        kamiConfigMapper.selectById(Long.parseLong(configIdText.trim()));
                if (kamiConfig == null || !accountId.equals(kamiConfig.getXianyuAccountId())) {
                    throw new IllegalArgumentException("卡密仓库不存在或不属于当前账号");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("卡密仓库参数格式错误");
            }
        }
    }
}
