package com.yoox.service.wayline.service.impl;

import com.yoox.great.context.enums.device.DeviceEnum;
import com.yoox.great.mqtt.enums.wayline.WaylineTypeEnum;
import com.yoox.great.mqtt.model.wayline.GetWaylineListResponse;
import com.yoox.service.wayline.model.dto.WaylineFileDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaylineFileServiceImplTest {

    @Test
    void acceptsLegacyAutelXl806WaylineMetadata() throws Exception {
        String template = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns:wpml="http://www.autel.com/wpmz/1.0.0" xmlns="http://www.opengis.net/kml/2.2">
                  <Document>
                    <Folder><wpml:templateType>waypoint</wpml:templateType></Folder>
                    <wpml:missionConfig>
                      <wpml:droneInfo>
                        <wpml:droneEnumValue>67</wpml:droneEnumValue>
                        <wpml:droneSubEnumValue>0</wpml:droneSubEnumValue>
                      </wpml:droneInfo>
                      <wpml:payloadInfo>
                        <wpml:payloadEnumValue>806</wpml:payloadEnumValue>
                        <wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue>
                      </wpml:payloadInfo>
                    </wpml:missionConfig>
                  </Document>
                </kml>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "新建任务-2026.08.06-185855.kmz", "application/vnd.google-earth.kmz", kmz(template));
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();

        Optional<WaylineFileDTO> result = ReflectionTestUtils.invokeMethod(service, "validKmzFile", file);

        assertTrue(result.isPresent());
        assertEquals("新建任务-2026.08.06-185855", result.orElseThrow().getName());
        assertEquals("0-11000-0", result.orElseThrow().getDroneModelKey());
        assertEquals(java.util.List.of("1-10806-0"), result.orElseThrow().getPayloadModelKeys());
    }

    @Test
    void acceptsDotsAndUnderscoresInWaylineDisplayName() {
        long now = System.currentTimeMillis();
        GetWaylineListResponse response = new GetWaylineListResponse()
                .setName("新建任务_2026.08.06-185855")
                .setId("12345678-1234-1234-1234-123456789abc")
                .setDroneModelKey(DeviceEnum.find("0-11000-0"))
                .setPayloadModelKeys(java.util.List.of(DeviceEnum.find("1-10806-0")))
                .setFavorited(false)
                .setTemplateTypes(java.util.List.of(WaylineTypeEnum.find("waypoint")))
                .setObjectKey("wayline/新建任务-2026.08.06-185855.kmz")
                .setUsername("test")
                .setUpdateTime(now)
                .setCreateTime(now);

        assertDoesNotThrow(() -> response.valid());
    }

    private byte[] kmz(String template) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zip.write(template.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
