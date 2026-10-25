package com.ledgame.platform;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/member-levels")
@CrossOrigin(originPatterns = {"http://127.0.0.1:[*]", "http://localhost:[*]"})
public class MemberLevelController {
    private final MemberLevelService levels;
    private final OperatorAuthorizationService authorization;

    public MemberLevelController(MemberLevelService levels, OperatorAuthorizationService authorization) {
        this.levels = levels;
        this.authorization = authorization;
    }

    @GetMapping
    public List<Map<String,Object>> list(@RequestHeader(value="X-Operator-Id", required=false) Long operatorId) {
        authorization.require(operatorId);
        return levels.list();
    }

    @PostMapping
    public Map<String,Object> create(@RequestHeader(value="X-Operator-Id", required=false) Long operatorId,
            @RequestBody LevelRequest body, HttpServletRequest request) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        var result = levels.save(null, body.name(), body.minPoints(), body.maxPoints());
        request.setAttribute(OperatorAuditInterceptor.TARGET_ID_ATTRIBUTE, String.valueOf(result.get("id")));
        return result;
    }

    @PutMapping("/{id}")
    public Map<String,Object> update(@PathVariable long id,
            @RequestHeader(value="X-Operator-Id", required=false) Long operatorId, @RequestBody LevelRequest body) {
        authorization.requireCapability(operatorId, OperatorCapability.OPERATIONS_VIEW);
        return levels.save(id, body.name(), body.minPoints(), body.maxPoints());
    }

    @DeleteMapping("/{id}")
    public Map<String,Object> delete(@PathVariable long id,
            @RequestHeader(value="X-Operator-Id", required=false) Long operatorId) {
        authorization.requireFactory(operatorId);
        return levels.delete(id);
    }

    public record LevelRequest(String name, BigDecimal minPoints, BigDecimal maxPoints) {}
}
