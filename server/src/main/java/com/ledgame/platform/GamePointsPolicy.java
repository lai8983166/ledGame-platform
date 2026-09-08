package com.ledgame.platform;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GamePointsPolicy {
    public static final String RAW_SCORE_VERSION = "raw-score-v1";
    public static final String LEVEL_CLEAR_VERSION = "level-clear-points-v1";
    public static final int MAX_REWARD_POINTS = 1_000_000;

    public AwardDecision award(boolean completedNaturally, Integer rawScore) {
        return award(completedNaturally, rawScore, null);
    }

    public AwardDecision award(
            boolean completedNaturally,
            Integer rawScore,
            GameScoringInput scoringInput) {
        if (scoringInput != null) {
            return levelClearAward(completedNaturally, scoringInput);
        }
        int points = completedNaturally && rawScore != null ? Math.max(0, rawScore) : 0;
        return new AwardDecision(points, RAW_SCORE_VERSION, null);
    }

    private AwardDecision levelClearAward(boolean completedNaturally, GameScoringInput input) {
        if (!LEVEL_CLEAR_VERSION.equals(input.version())) {
            throw invalid("不支持的积分依据版本");
        }
        if (input.awardEligible() == null || input.totalPoints() == null || input.levels() == null) {
            throw invalid("积分依据缺少必填字段");
        }
        if (input.totalPoints() < 0) throw invalid("累计积分不能为负数");
        List<GameScoringInput.LevelAward> levels = input.levels();
        Set<Integer> indexes = new HashSet<>();
        long total = 0;
        for (GameScoringInput.LevelAward level : levels) {
            if (level == null || level.levelIndex() == null || level.levelIndex() < 0
                    || !indexes.add(level.levelIndex())) {
                throw invalid("关卡索引必须非负且不能重复");
            }
            int reward = requirePoints(level.rewardPoints(), "关卡奖励积分");
            int awarded = requirePoints(level.awardedPoints(), "关卡实发积分");
            if (awarded > reward) throw invalid("关卡实发积分不能超过配置积分");
            total += awarded;
            if (total > Integer.MAX_VALUE) throw invalid("累计积分超出范围");
        }
        if (total != input.totalPoints()) throw invalid("累计积分与逐关明细合计不一致");
        int awarded = completedNaturally && Boolean.TRUE.equals(input.awardEligible())
                ? input.totalPoints() : 0;
        return new AwardDecision(awarded, LEVEL_CLEAR_VERSION, input);
    }

    private static int requirePoints(Integer value, String field) {
        if (value == null || value < 0 || value > MAX_REWARD_POINTS) {
            throw invalid(field + "必须在 0 到 " + MAX_REWARD_POINTS + " 之间");
        }
        return value;
    }

    private static PlatformApiException invalid(String message) {
        return GameAccessService.error(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_SCORING_INPUT",
                message);
    }

    public record AwardDecision(int points, String version, GameScoringInput scoringInput) {}
}
