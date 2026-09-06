package com.ledgame.platform;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ActivationStartupRunner implements ApplicationRunner {
    private final ActivationService activation;
    private final DatabaseBackupCoordinator backup;
    public ActivationStartupRunner(ActivationService activation, DatabaseBackupCoordinator backup) {
        this.activation = activation; this.backup = backup;
    }
    @Override public void run(ApplicationArguments args) {
        activation.whenActivated(backup::startAfterActivation);
    }
}
