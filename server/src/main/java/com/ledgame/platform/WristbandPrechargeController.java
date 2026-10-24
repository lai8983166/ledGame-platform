package com.ledgame.platform;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wristband-precharges")
@CrossOrigin(originPatterns={"http://127.0.0.1:[*]","http://localhost:[*]"})
public class WristbandPrechargeController {
    private final WristbandPrechargeService service;
    private final OperatorAuthorizationService authorization;

    public WristbandPrechargeController(WristbandPrechargeService service, OperatorAuthorizationService authorization) {
        this.service=service;
        this.authorization=authorization;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestHeader(value="X-Operator-Id",required=false) Long operatorId,
            @RequestParam(required=false) String uid,@RequestParam(required=false) String status) {
        authorization.requireCapability(operatorId,OperatorCapability.WRISTBAND_MANAGE);
        return service.list(uid,status);
    }

    @PostMapping
    public Map<String,Object> prepare(@RequestHeader(value="X-Operator-Id",required=false) Long operatorId,
            @RequestBody Prepare request,HttpServletRequest servlet) {
        var result=service.prepare(request.uid(),request.durationMinutes(),request.requestId(),authorization.requireCapability(operatorId,OperatorCapability.WRISTBAND_MANAGE));
        servlet.setAttribute(OperatorAuditInterceptor.TARGET_ID_ATTRIBUTE,String.valueOf(result.get("id")));
        return result;
    }

    @PostMapping("/{id}/edit")
    public Map<String,Object> edit(@PathVariable long id,@RequestHeader(value="X-Operator-Id",required=false) Long operatorId,@RequestBody Change request) {
        authorization.requireCapability(operatorId,OperatorCapability.WRISTBAND_MANAGE);
        return service.change(id,request.version(),request.durationMinutes(),false);
    }

    @PostMapping("/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable long id,@RequestHeader(value="X-Operator-Id",required=false) Long operatorId,@RequestBody Change request) {
        authorization.requireCapability(operatorId,OperatorCapability.WRISTBAND_MANAGE);
        return service.change(id,request.version(),null,true);
    }

    public record Prepare(String uid,Integer durationMinutes,String requestId) {}
    public record Change(Integer version,Integer durationMinutes) {}
}
