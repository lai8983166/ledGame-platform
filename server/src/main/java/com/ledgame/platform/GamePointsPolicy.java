package com.ledgame.platform;

import org.springframework.stereotype.Component;

@Component
public class GamePointsPolicy {
    public static final String VERSION = "raw-score-v1";

    public AwardDecision award(boolean completedNaturally, Integer rawScore) {
        int points = completedNaturally && rawScore != null ? Math.max(0, rawScore) : 0;
        return new AwardDecision(points, VERSION);
    }

    public record AwardDecision(int points, String version) {}
}
