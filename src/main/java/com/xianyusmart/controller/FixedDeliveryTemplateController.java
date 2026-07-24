package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.FixedDeliveryTemplateReqDTO;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.service.FixedDeliveryTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fixed-delivery-template")
public class FixedDeliveryTemplateController {

    private final FixedDeliveryTemplateService templateService;

    public FixedDeliveryTemplateController(FixedDeliveryTemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping("/save")
    public ResultObject<XianyuFixedDeliveryTemplate> save(@Valid @RequestBody FixedDeliveryTemplateReqDTO request) {
        return templateService.save(request);
    }

    @GetMapping("/list")
    public ResultObject<List<XianyuFixedDeliveryTemplate>> list(@RequestParam Long xianyuAccountId) {
        return templateService.list(xianyuAccountId);
    }

    @PostMapping("/delete")
    public ResultObject<Void> delete(@RequestParam Long xianyuAccountId, @RequestParam Long id) {
        return templateService.delete(xianyuAccountId, id);
    }
}
