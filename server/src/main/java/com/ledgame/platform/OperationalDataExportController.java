package com.ledgame.platform;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exports")
public class OperationalDataExportController {
    private final OperationalDataExportService exportService;
    private final OperatorAuthorizationService authorization;

    public OperationalDataExportController(OperationalDataExportService exportService,
                                           OperatorAuthorizationService authorization) {
        this.exportService = exportService;
        this.authorization = authorization;
    }

    @GetMapping(value = "/members.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> members(@RequestHeader("X-Operator-Id") Long operatorId) {
        authorization.require(operatorId);
        return response("members.csv", exportService.members());
    }

    @GetMapping(value = "/wristband-charges.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> wristbandCharges(@RequestHeader("X-Operator-Id") Long operatorId) {
        authorization.require(operatorId);
        return response("wristband-charges.csv", exportService.wristbandCharges());
    }

    @GetMapping(value = "/game-plays.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> gamePlays(@RequestHeader("X-Operator-Id") Long operatorId) {
        authorization.require(operatorId);
        return response("game-plays.csv", exportService.gamePlays());
    }

    private static ResponseEntity<byte[]> response(String filename, byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
