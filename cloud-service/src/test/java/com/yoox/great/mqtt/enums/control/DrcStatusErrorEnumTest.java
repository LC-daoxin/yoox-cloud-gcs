package com.yoox.great.mqtt.enums.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.context.base.Common;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DrcStatusErrorEnumTest {

    private final ObjectMapper mapper = Common.getObjectMapper();

    @Test
    void acceptsNumericCode() throws Exception {
        assertEquals(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT,
                mapper.readValue("514301", DrcStatusErrorEnum.class));
    }

    @Test
    void acceptsNumericString() throws Exception {
        assertEquals(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT,
                mapper.readValue("\"514301\"", DrcStatusErrorEnum.class));
    }

    @Test
    void acceptsSymbolicEnumName() throws Exception {
        assertEquals(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT,
                mapper.readValue("\"HEARTBEAT_TIMEOUT\"", DrcStatusErrorEnum.class));
    }

    @Test
    void keepsCanonicalWireRepresentationNumeric() throws Exception {
        assertEquals("514301", mapper.writeValueAsString(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT));
    }
}
