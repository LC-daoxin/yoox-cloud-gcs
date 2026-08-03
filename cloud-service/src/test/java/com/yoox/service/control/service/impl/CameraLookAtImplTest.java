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

    @Test
    void mqttRequestSerializesOnlyOfficialRcCoordinates() {
        DronePayloadParam param = lookAtParam();
        CameraLookAtRequest request = mapper.convertValue(param, CameraLookAtRequest.class);

        JsonNode json = mapper.valueToTree(request);

        assertEquals(3, json.size());
        assertEquals(22.608532f, json.get("latitude").floatValue());
        assertEquals(113.83196f, json.get("longitude").floatValue());
        assertEquals(41.695f, json.get("height").floatValue());
        assertFalse(json.has("payload_index"));
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
