package com.yoox.great.mqtt.model.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HsiInfoPushTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void autelSnakeCaseRadarPayloadRetainsMetreValuesAndMinusOneSentinel() throws Exception {
        HsiInfoPush telemetry = mapper.readValue("""
                {
                  "front1_distance": 1.5,
                  "front2_distance": -1,
                  "rear4_distance": 8,
                  "radar_enable": true
                }
                """, HsiInfoPush.class);

        assertEquals(1.5, telemetry.getFront1Distance().doubleValue());
        assertEquals(-1, telemetry.getFront2Distance().intValue());
        assertEquals(8, telemetry.getRear4Distance().intValue());
        assertTrue(telemetry.getRadarEnable());
    }
}
