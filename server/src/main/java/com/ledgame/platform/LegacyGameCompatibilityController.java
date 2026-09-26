package com.ledgame.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/dev")
@CrossOrigin(origins = "*")
public class LegacyGameCompatibilityController {
    private static final Logger log = LoggerFactory.getLogger(LegacyGameCompatibilityController.class);
    private final LegacyGameCompatibilityService compatibility;
    private final ObjectMapper mapper;

    public LegacyGameCompatibilityController(LegacyGameCompatibilityService compatibility, ObjectMapper mapper) {
        this.compatibility = compatibility;
        this.mapper = mapper;
    }

    @GetMapping("/ping")
    public LegacyPing ping() {
        return new LegacyPing("pong");
    }

    @PostMapping(value = "/gameCallback", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LegacyGameCompatibilityService.LegacyResponse callback(
            @RequestBody(required = false) String body, HttpServletRequest request) {
        String sourceIp = request.getRemoteAddr();
        String command = "unparsed";
        try {
            JsonNode payload = body == null || body.isBlank() ? mapper.getNodeFactory().nullNode() : mapper.readTree(body);
            JsonNode commandNode = payload.path("cmd");
            if (commandNode.isIntegralNumber() && commandNode.canConvertToInt()) command = commandNode.asText();
            return compatibility.handle(payload, sourceIp);
        } catch (JsonProcessingException exception) {
            String correlationId = UUID.randomUUID().toString();
            log.warn("legacy_callback_rejected cmd=unparsed roomIp={} correlationId={} code=LEGACY_JSON_INVALID",
                    RoomConnectionRegistry.normalizeIp(sourceIp), correlationId);
            return LegacyGameCompatibilityService.LegacyResponse.failure(400, "Invalid JSON callback body");
        } catch (PlatformApiException exception) {
            String correlationId = UUID.randomUUID().toString();
            log.warn("legacy_callback_rejected cmd={} roomIp={} correlationId={} code={}", command,
                    RoomConnectionRegistry.normalizeIp(sourceIp), correlationId, exception.getCode());
            return LegacyGameCompatibilityService.LegacyResponse.failure(
                    exception.getStatusCode().value(), exception.getReason());
        } catch (RuntimeException exception) {
            String correlationId = UUID.randomUUID().toString();
            log.error("legacy_callback_failed cmd={} roomIp={} correlationId={} code=LEGACY_INTERNAL_ERROR",
                    command, RoomConnectionRegistry.normalizeIp(sourceIp), correlationId);
            return LegacyGameCompatibilityService.LegacyResponse.failure(500,
                    "Legacy callback could not be processed");
        }
    }

    public record LegacyPing(String data) {}
}
