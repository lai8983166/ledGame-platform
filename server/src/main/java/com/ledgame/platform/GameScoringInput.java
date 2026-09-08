package com.ledgame.platform;

import java.util.List;

public record GameScoringInput(
        String version,
        Boolean awardEligible,
        Integer totalPoints,
        List<LevelAward> levels) {

    public record LevelAward(
            Integer levelIndex,
            Integer rewardPoints,
            Integer awardedPoints,
            String reason) {}
}
