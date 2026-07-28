package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.OperationsDiagnosticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AuthService authService;

    public OperationsDiagnosticsController(OperationsDiagnosticsService diagnosticsService,
                                           AuthService authService) {
        this.diagnosticsService = diagnosticsService;
        this.authService = authService;
    }

    @GetMapping("/overview")
    public ResultObject<Map<String, Object>> overview(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        SysUser user = userId == null ? null : authService.getCurrentUser(userId);
        boolean platformAdmin = user != null
                && Integer.valueOf(1).equals(user.getStatus())
                && SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole());
        return ResultObject.success(diagnosticsService.overview(platformAdmin));
    }

    @GetMapping("/exceptions")
    public ResultObject<List<Map<String, Object>>> exceptions(
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(diagnosticsService.exceptions(limit));
    }

    @PostMapping("/exceptions/acknowledge")
    public ResultObject<Void> acknowledgeException(@Valid @RequestBody AcknowledgeExceptionRequest request) {
        diagnosticsService.acknowledgeException(
                request.exceptionType(), request.exceptionId(), request.exceptionVersion());
        return ResultObject.success(null);
    }

    @PostMapping("/exceptions/acknowledge-all")
    public ResultObject<Integer> acknowledgeAllExceptions(
            @Valid @RequestBody @Size(min = 1, max = 200) List<AcknowledgeExceptionRequest> requests) {
        List<OperationsDiagnosticsService.ExceptionReference> references = requests.stream()
                .map(request -> new OperationsDiagnosticsService.ExceptionReference(
                        request.exceptionType(), request.exceptionId(), request.exceptionVersion()))
                .toList();
        return ResultObject.success(diagnosticsService.acknowledgeExceptions(references));
    }

    public record AcknowledgeExceptionRequest(
            @NotBlank String exceptionType,
            @NotNull @Positive Long exceptionId,
            @NotNull @PositiveOrZero Integer exceptionVersion) {
    }
}
