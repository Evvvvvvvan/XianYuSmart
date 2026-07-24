package com.xianyusmart.service;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.FixedDeliveryTemplateReqDTO;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuFixedDeliveryTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 固定内容模板管理
 */
@Service
public class FixedDeliveryTemplateService {

    private final XianyuFixedDeliveryTemplateMapper templateMapper;
    private final XianyuAccountMapper accountMapper;
    private final BuyerMessageService buyerMessageService;

    public FixedDeliveryTemplateService(XianyuFixedDeliveryTemplateMapper templateMapper,
                                        XianyuAccountMapper accountMapper,
                                        BuyerMessageService buyerMessageService) {
        this.templateMapper = templateMapper;
        this.accountMapper = accountMapper;
        this.buyerMessageService = buyerMessageService;
    }

    @Transactional
    public ResultObject<XianyuFixedDeliveryTemplate> save(FixedDeliveryTemplateReqDTO request) {
        try {
            requireOwnedAccount(request.getXianyuAccountId());
            String name = normalizeRequired(request.getTemplateName(), "模板名称", 100);
            String content = normalizeRequired(request.getDeliveryContent(), "全部发货内容", 5000);
            String messageTemplate = buyerMessageService.normalizeDeliveryMessageTemplate(
                    request.getMessageTemplate());

            XianyuFixedDeliveryTemplate template;
            if (request.getId() == null) {
                template = new XianyuFixedDeliveryTemplate();
                template.setXianyuAccountId(request.getXianyuAccountId());
            } else {
                template = findOwnedTemplate(request.getXianyuAccountId(), request.getId());
                if (template == null) {
                    return ResultObject.failed("固定内容模板不存在");
                }
            }
            template.setTemplateName(name);
            template.setDeliveryContent(content);
            template.setMessageTemplate(messageTemplate);
            if (template.getId() == null) {
                templateMapper.insert(template);
            } else {
                templateMapper.updateById(template);
            }
            return ResultObject.success(template);
        } catch (Exception e) {
            return ResultObject.failed("保存固定内容模板失败: " + e.getMessage());
        }
    }

    public ResultObject<List<XianyuFixedDeliveryTemplate>> list(Long accountId) {
        try {
            requireOwnedAccount(accountId);
            return ResultObject.success(templateMapper.findByAccountId(accountId));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @Transactional
    public ResultObject<Void> delete(Long accountId, Long id) {
        try {
            requireOwnedAccount(accountId);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
        XianyuFixedDeliveryTemplate template = findOwnedTemplate(accountId, id);
        if (template == null) {
            return ResultObject.failed("固定内容模板不存在");
        }
        if (templateMapper.countReferencedConfigs(id) > 0) {
            return ResultObject.failed("模板正在被商品使用，请先为商品更换模板");
        }
        templateMapper.deleteById(id);
        return ResultObject.success(null);
    }

    public XianyuFixedDeliveryTemplate findOwnedTemplate(Long accountId, Long id) {
        if (accountId == null || id == null) {
            return null;
        }
        return templateMapper.findOwnedById(accountId, id);
    }

    private String normalizeRequired(String value, String fieldName, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private void requireOwnedAccount(Long accountId) {
        if (accountId == null || accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("闲鱼账号不存在或无权访问");
        }
    }
}
