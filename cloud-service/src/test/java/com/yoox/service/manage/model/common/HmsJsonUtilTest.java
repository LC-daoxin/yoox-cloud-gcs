package com.yoox.service.manage.model.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HmsJsonUtilTest {

    @BeforeAll
    static void loadOfficialHmsDictionary() {
        HmsJsonUtil util = new HmsJsonUtil();
        util.setMapper(new ObjectMapper());
        util.loadJsonFile();
    }

    @Test
    void resolvesOfficialAutelAircraftAlarmInChinese() {
        assertEquals("飞机姿态异常，请尽快降落",
                HmsJsonUtil.get("fpv_tip_0x0001").getZh());
    }

    @Test
    void resolvesDockAlarmRegardlessOfHexPrefixCase() {
        assertEquals("电机电源异常",
                HmsJsonUtil.get("dock_tip_0x1001").getZh());
    }

    @Test
    void fallsBackToBaseAlarmForAirborneAutelKey() {
        assertEquals("飞机姿态异常，请尽快降落",
                HmsJsonUtil.get("fpv_tip_0x0001_in_the_sky").getZh());
    }

    @Test
    void resolvesOfficialAutelRemoteControllerAlarm() {
        assertEquals("遥控器摇杆异常，为保证飞行安全请先校准后起飞",
                HmsJsonUtil.get("rc_tip_0x00010000").getZh());
    }
}
