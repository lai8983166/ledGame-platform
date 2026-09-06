package com.ledgame.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class ActivatedTestConfiguration implements org.springframework.beans.factory.DisposableBean {
    private Path directory;
    @Bean @Primary public ActivationService fixtureActivation() throws Exception {
        directory = Files.createTempDirectory("ledgame-activation-test-");
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String machine = WindowsMachineIdentity.machineCode("11111111-2222-3333-4444-555555555555");
        var service = new ActivationService(directory, () -> machine, keys::getPublic);
        service.activate(code(keys, machine));
        return service;
    }
    static String code(KeyPair keys, String machine) throws Exception {
        byte[] payload = new ObjectMapper().writeValueAsBytes(Map.of("formatVersion", 1, "product", "ledgame-member-admin",
                "machineCode", machine, "licenseId", "AUTOMATED-TEST", "issuedAt", "2026-01-01T00:00:00Z"));
        var signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate()); signer.update(payload);
        return "LGACT1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
    @Override public void destroy() throws Exception {
        if (directory == null) return;
        Files.deleteIfExists(directory.resolve("license.json"));
        Files.deleteIfExists(directory);
    }
}
