package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WindowsDiskTopologyProviderTest {
    @Test
    void parsesStructuredPowerShellJsonWithoutLocalizedText() {
        String json = """
            [{"mountPoint":"C:\\\\","uniqueId":"磁盘-A","serialNumber":"序列号一",\
              "diskNumber":0,"busType":"NVMe","driveType":"Fixed","fileSystem":"NTFS",\
              "readOnly":false,"freeBytes":123456789},\
             {"mountPoint":"D:\\\\","uniqueId":"磁盘-B","serialNumber":"序列号二",\
              "diskNumber":1,"busType":"SATA","driveType":"Fixed","fileSystem":"NTFS",\
              "readOnly":false,"freeBytes":987654321}]
            """;

        var volumes = new WindowsDiskTopologyProvider(new ObjectMapper()).parse(json);

        assertThat(volumes).hasSize(2);
        assertThat(volumes.get(0).uniqueId()).isEqualTo("磁盘-A");
        assertThat(volumes.get(1).diskNumber()).isEqualTo(1);
        assertThat(volumes.get(1).freeBytes()).isEqualTo(987654321L);
    }

    @Test
    void acceptsSingleObjectJsonReturnedByPowerShell() {
        String json = """
            {"mountPoint":"C:\\\\","uniqueId":"one","serialNumber":"",\
             "diskNumber":0,"busType":"NVMe","driveType":"Fixed","fileSystem":"NTFS",\
             "readOnly":false,"freeBytes":1000}
            """;
        assertThat(new WindowsDiskTopologyProvider(new ObjectMapper()).parse(json)).hasSize(1);
    }
}
