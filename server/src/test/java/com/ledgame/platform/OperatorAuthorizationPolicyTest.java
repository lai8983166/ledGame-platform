package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OperatorAuthorizationPolicyTest {
    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @MethodSource("matrix")
    void capabilityMatrixIsExplicitForEveryRole(
            OperatorRole role, OperatorCapability capability, boolean expected) {
        assertThat(OperatorAuthorizationService.allowed(role, capability)).isEqualTo(expected);
    }

    private static Stream<Arguments> matrix() {
        Set<OperatorCapability> manager = Set.of(
                OperatorCapability.WRISTBAND_MANAGE,
                OperatorCapability.OPERATIONS_VIEW,
                OperatorCapability.FEATURE_SETTINGS,
                OperatorCapability.DATA_EXPORT);
        Set<OperatorCapability> clerk = Set.of(
                OperatorCapability.WRISTBAND_MANAGE,
                OperatorCapability.FEATURE_SETTINGS);
        return Stream.of(OperatorRole.values()).flatMap(role ->
                Stream.of(OperatorCapability.values()).map(capability -> Arguments.of(
                        role,
                        capability,
                        role == OperatorRole.FACTORY_ADMIN
                                || (role == OperatorRole.STORE_MANAGER && manager.contains(capability))
                                || (role == OperatorRole.CLERK && clerk.contains(capability)))));
    }
}
