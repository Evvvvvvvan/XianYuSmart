package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.OperationsDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运营诊断接口
 */
@RestController
@RequestMapping("/api/diagnostics")
public class OperationsDiagnosticsController {

    private final OperationsDiagnosticsService diagnosticsService;

    public OperationsDiagnosticsController(OperationsDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping("/overview")
    public ResultObject<Map<String, Object>> overview() {
        return ResultObject.success(diagnosticsService.overview());
    }

    @GetMapping("/exceptions")
    public ResultObject<List<Map<String, Object>>> exceptions(
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(diagnosticsService.exceptions(limit));
    }
}
