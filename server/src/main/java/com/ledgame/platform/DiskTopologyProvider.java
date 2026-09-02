package com.ledgame.platform;

import java.util.List;

public interface DiskTopologyProvider {
    List<DiskVolume> discover();
}
