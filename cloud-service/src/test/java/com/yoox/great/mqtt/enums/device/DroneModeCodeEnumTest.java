package com.yoox.great.mqtt.enums.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DroneModeCodeEnumTest {

    @Test
    void findsNonOrdinalKmlRouteModeByProtocolCode() {
        assertEquals(DroneModeCodeEnum.KML_ROUTE_MODE, DroneModeCodeEnum.find(39));
    }
}
