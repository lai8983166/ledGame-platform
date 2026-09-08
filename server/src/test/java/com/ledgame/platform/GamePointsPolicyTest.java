package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GamePointsPolicyTest {
    private final GamePointsPolicy policy = new GamePointsPolicy();

    @Test
    void keepsRawScoreCompatibilityWhenScoringInputIsMissing() {
        assertThat(policy.award(true, 12, null))
                .extracting(GamePointsPolicy.AwardDecision::points,
                        GamePointsPolicy.AwardDecision::version)
                .containsExactly(12, "raw-score-v1");
    }

    @Test
    void awardsValidatedLevelPointsSeparatelyFromRawScore() {
        GameScoringInput input = input(true, 30, List.of(
                level(0, 10, 10), level(1, 20, 20)));

        var award = policy.award(true, 120, input);

        assertThat(award.points()).isEqualTo(30);
        assertThat(award.version()).isEqualTo("level-clear-points-v1");
    }

    @Test
    void nonNaturalTerminationAlwaysAwardsZero() {
        assertThat(policy.award(false, 120, input(true, 30,
                List.of(level(0, 30, 30)))).points()).isZero();
    }

    @Test
    void rejectsMismatchedOrDuplicateLevelEvidence() {
        assertThatThrownBy(() -> policy.award(true, 1,
                input(true, 99, List.of(level(0, 10, 10)))))
                .isInstanceOf(PlatformApiException.class)
                .hasMessageContaining("合计");
        assertThatThrownBy(() -> policy.award(true, 1,
                input(true, 20, List.of(level(0, 10, 10), level(0, 10, 10)))))
                .isInstanceOf(PlatformApiException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void naturalFailureKeepsClearedLevelPointsAndOversizedTotalsAreRejected() {
        assertThat(policy.award(true, 0, input(true, 15, List.of(level(0, 15, 15)))).points())
                .isEqualTo(15);
        List<GameScoringInput.LevelAward> overflow = IntStream.range(0, 2_148)
                .mapToObj(index -> level(index, 1_000_000, 1_000_000))
                .toList();
        assertThatThrownBy(() -> policy.award(true, 0, input(true, 0, overflow)))
                .isInstanceOf(PlatformApiException.class)
                .hasMessageContaining("范围");
    }

    private static GameScoringInput input(boolean eligible, int total,
                                          List<GameScoringInput.LevelAward> levels) {
        return new GameScoringInput("level-clear-points-v1", eligible, total, levels);
    }

    private static GameScoringInput.LevelAward level(int index, int reward, int awarded) {
        return new GameScoringInput.LevelAward(index, reward, awarded, "CLEARED");
    }
}
