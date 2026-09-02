package com.ledgame.platform;

import java.nio.file.Path;

public record BackupTarget(DiskVolume volume, Path root) {}
