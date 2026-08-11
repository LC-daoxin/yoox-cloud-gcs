package com.yoox.service.control.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.context.base.Common;
import com.yoox.great.mqtt.model.control.CameraLookAtRequest;
import com.yoox.service.control.model.param.DronePayloadParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraLookAtImplTest {

    private final ObjectMapper mapper = Common.getObjectMapper();

    @Test
    void validLookAtDoesNotRequireLockedFlag() {
        DronePayloadParam param = lookAtParam();
        param.setLocked(null);

        assertTrue(new CameraLookAtImpl(param).valid());
    }

    /**
     * 实测（RC 固件 1.9.1.203）：camera_look_at 与 camera_screen_drag 一样，
     * 必须携带 payload_index 才能被遥控器路由到负载；缺失时指令被静默丢弃、
     * 永不回复 services_reply，云端表现为 211001 超时。
     */
    @Test
    void mqttRequestSerializesPayloadRoutingFields() {
        DronePayloadParam param = lookAtParam();
        CameraLookAtRequest request = mapper.convertValue(param, CameraLookAtRequest.class);

        JsonNode json = mapper.valueToTree(request);

        assertEquals(5, json.size());
        assertEquals("10806-0-0", json.get("payload_index").asText());
        assertFalse(json.get("locked").booleanValue());
        assertEquals(22.608532f, json.get("latitude").floatValue());
        assertEquals(113.83196f, json.get("longitude").floatValue());
        assertEquals(41.695f, json.get("height").floatValue());
    }

    /** locked 可空：为空时不序列化该字段，仍需保留坐标与 payload_index。 */
    @Test
    void mqttRequestOmitsAbsentLockedFlag() {
        DronePayloadParam param = lookAtParam();
        param.setLocked(null);
        CameraLookAtRequest request = mapper.convertValue(param, CameraLookAtRequest.class);

        JsonNode json = mapper.valueToTree(request);

        assertEquals(4, json.size());
        assertEquals("10806-0-0", json.get("payload_index").asText());
        assertFalse(json.has("locked"));
    }

    private DronePayloadParam lookAtParam() {
        DronePayloadParam param = new DronePayloadParam();
        param.setPayloadIndex("10806-0-0");
        param.setLocked(false);
        param.setLatitude(22.608532f);
        param.setLongitude(113.83196f);
        param.setHeight(41.695f);
        return param;
    }
}
