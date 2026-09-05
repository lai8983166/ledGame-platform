package com.ledgame.platform;

import java.util.Set;

final class RoomConnectionProtocol {
    static final String HELLO = "HELLO";
    static final String WELCOME = "WELCOME";
    static final String CHILD_MODE_CHANGED = "CHILD_MODE_CHANGED";
    static final String ROOM_SNAPSHOT = "ROOM_SNAPSHOT";
    static final String GAME_STARTED = "GAME_STARTED";
    static final String GAME_TIMING_CHANGED = "GAME_TIMING_CHANGED";
    static final String QUEUE_CHANGED = "QUEUE_CHANGED";
    static final String GAME_ENDED = "GAME_ENDED";
    static final String ACK = "ACK";
    static final String ERROR = "ERROR";
    static final Set<String> EVENT_TYPES = Set.of(
            ROOM_SNAPSHOT,
            GAME_STARTED,
            GAME_TIMING_CHANGED,
            QUEUE_CHANGED,
            GAME_ENDED
    );

    private RoomConnectionProtocol() {}
}
