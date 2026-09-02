package com.ledgame.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class WindowsDiskTopologyProvider implements DiskTopologyProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final String SCRIPT = """
        $ErrorActionPreference='Stop';
        $items = @(Get-Partition | Where-Object { $_.DriveLetter } | ForEach-Object {
          $p = $_; $d = Get-Disk -Number $p.DiskNumber; $v = Get-Volume -DriveLetter $p.DriveLetter;
          [PSCustomObject]@{
            mountPoint = ([string]$p.DriveLetter + ':\\');
            uniqueId = [string]$d.UniqueId;
            serialNumber = [string]$d.SerialNumber;
            diskNumber = [int]$d.Number;
            busType = [string]$d.BusType;
            driveType = [string]$v.DriveType;
            fileSystem = [string]$v.FileSystem;
            readOnly = [bool]$d.IsReadOnly;
            freeBytes = [long]$v.SizeRemaining
          }
        });
        $items | ConvertTo-Json -Compress
        """;

    private final ObjectMapper objectMapper;

    public WindowsDiskTopologyProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DiskVolume> discover() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new IllegalStateException(BackupErrorCode.UNSUPPORTED_PLATFORM.name());
        }
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", SCRIPT)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PowerShell disk discovery timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new IllegalStateException(output);
            return parse(output);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to run PowerShell disk discovery", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Disk discovery interrupted", exception);
        }
    }

    List<DiskVolume> parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json.isBlank() ? "[]" : json);
            List<DiskVolume> result = new ArrayList<>();
            if (root.isArray()) root.forEach(node -> result.add(volume(node)));
            else if (root.isObject()) result.add(volume(root));
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse Windows disk topology", exception);
        }
    }

    private static DiskVolume volume(JsonNode node) {
        return new DiskVolume(
                Path.of(node.path("mountPoint").asText()),
                node.path("uniqueId").asText(""),
                node.path("serialNumber").asText(""),
                node.path("diskNumber").asInt(-1),
                node.path("busType").asText(""),
                node.path("driveType").asText(""),
                node.path("fileSystem").asText(""),
                node.path("readOnly").asBoolean(false),
                node.path("freeBytes").asLong(0));
    }
}
