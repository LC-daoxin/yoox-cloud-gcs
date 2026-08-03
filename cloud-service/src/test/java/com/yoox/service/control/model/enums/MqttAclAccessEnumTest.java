package com.yoox.service.control.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MqttAclAccessEnumTest {

    @Test
    void usesEmqxFiveActionNames() {
        assertEquals("subscribe", MqttAclAccessEnum.SUB.getValue());
        assertEquals("publish", MqttAclAccessEnum.PUB.getValue());
        assertEquals("all", MqttAclAccessEnum.ALL.getValue());
    }
}
