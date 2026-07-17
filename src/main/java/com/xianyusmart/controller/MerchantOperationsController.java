package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.MerchantDistributionReqDTO;
import com.xianyusmart.controller.dto.MerchantResourceReqDTO;
import com.xianyusmart.controller.dto.MerchantResourceRespDTO;
import com.xianyusmart.controller.dto.MerchantTaskReqDTO;
import com.xianyusmart.entity.MerchantDistribution;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.service.MerchantOperationsService;
import lombok.extern.slf4j.Slf4j;
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
 * 商家运营中心接口
 */
@Slf4j
@RestController
@RequestMapping("/api/merchant")
public class MerchantOperationsController {

    private final MerchantOperationsService operationsService;

    public MerchantOperationsController(MerchantOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/overview")
    public ResultObject<Map<String, Object>> getOverview() {
        try {
            return ResultObject.success(operationsService.getOverview());
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/resources")
    public ResultObject<List<MerchantResourceRespDTO>> listResources(@RequestParam String type,
                                                                     @RequestParam(required = false) Integer status) {
        try {
            return ResultObject.success(operationsService.listResources(type, status));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/resources")
    public ResultObject<MerchantResourceRespDTO> saveResource(@RequestBody MerchantResourceReqDTO request) {
        try {
            return ResultObject.success(operationsService.saveResource(request));
        } catch (Exception e) {
            log.warn("保存商家运营资源失败: {}", e.getMessage());
            return ResultObject.failed(e.getMessage());
        }
    }

    @DeleteMapping("/resources/{id}")
    public ResultObject<Void> deleteResource(@PathVariable Long id) {
        try {
            operationsService.deleteResource(id);
            return ResultObject.success(null);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/resources/{id}/execute")
    public ResultObject<MerchantTask> executeResource(@PathVariable Long id) {
        try {
            return ResultObject.success(operationsService.executeResource(id));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/resources/{id}/compensate")
    public ResultObject<MerchantTask> compensateResource(@PathVariable Long id) {
        try {
            return ResultObject.success(operationsService.compensateResource(id));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/supplies/{id}/material")
    public ResultObject<MerchantResourceRespDTO> convertSupplyToMaterial(@PathVariable Long id) {
        try {
            return ResultObject.success(operationsService.convertSupplyToMaterial(id));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/tasks")
    public ResultObject<List<MerchantTask>> listTasks(@RequestParam(required = false) String taskType,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam(required = false) Integer limit) {
        try {
            return ResultObject.success(operationsService.listTasks(taskType, status, limit));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/tasks")
    public ResultObject<MerchantTask> createTask(@RequestBody MerchantTaskReqDTO request) {
        try {
            return ResultObject.success(operationsService.createTask(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/tasks/{id}/requeue")
    public ResultObject<Void> requeueTask(@PathVariable Long id) {
        try {
            operationsService.requeueTask(id);
            return ResultObject.success(null);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/tasks/batch-publish")
    public ResultObject<List<MerchantTask>> batchPublish(@RequestBody Map<String, Object> request) {
        try {
            return ResultObject.success(operationsService.batchPublish(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/distributions")
    public ResultObject<List<MerchantDistribution>> listDistributions(@RequestParam(required = false) Integer status,
                                                                       @RequestParam(required = false) Integer settlementStatus,
                                                                       @RequestParam(required = false) Integer limit) {
        return ResultObject.success(operationsService.listDistributions(status, settlementStatus, limit));
    }

    @PostMapping("/distributions")
    public ResultObject<MerchantDistribution> saveDistribution(@RequestBody MerchantDistributionReqDTO request) {
        try {
            return ResultObject.success(operationsService.saveDistribution(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/distributions/{id}/settle")
    public ResultObject<Void> settleDistribution(@PathVariable Long id) {
        try {
            operationsService.settleDistribution(id);
            return ResultObject.success(null);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }
}
