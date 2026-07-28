package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.BuyerProfileQueryReqDTO;
import com.xianyusmart.controller.dto.BuyerProfileDetailReqDTO;
import com.xianyusmart.controller.dto.BuyerProfileDetailRespDTO;
import com.xianyusmart.controller.dto.BuyerProfileRespDTO;
import com.xianyusmart.controller.dto.BuyerProfileSaveReqDTO;
import com.xianyusmart.service.BuyerProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 买家管理接口
 */
@RestController
@RequestMapping("/api/buyers")
public class BuyerProfileController {

    private final BuyerProfileService buyerProfileService;

    public BuyerProfileController(BuyerProfileService buyerProfileService) {
        this.buyerProfileService = buyerProfileService;
    }

    @PostMapping("/list")
    public ResultObject<Map<String, Object>> list(@RequestBody BuyerProfileQueryReqDTO request) {
        try {
            return ResultObject.success(buyerProfileService.list(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/save")
    public ResultObject<BuyerProfileRespDTO> save(@Valid @RequestBody BuyerProfileSaveReqDTO request) {
        try {
            return ResultObject.success(buyerProfileService.save(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/detail")
    public ResultObject<BuyerProfileDetailRespDTO> detail(
            @Valid @RequestBody BuyerProfileDetailReqDTO request) {
        try {
            return ResultObject.success(buyerProfileService.detail(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }
}
