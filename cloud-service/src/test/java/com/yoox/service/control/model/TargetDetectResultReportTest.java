package com.yoox.service.control.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.mqtt.model.control.TargetDetectResultReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetDetectResultReportTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void parsesNormalizedDetectionBox() throws Exception {
        String json = "{\"obj_cnt\":1,\"objs\":[{\"tracker_id\":\"t-1\",\"cls_id\":4,"
                + "\"bbox\":{\"x\":0.1,\"y\":0.2,\"w\":0.3,\"h\":0.4}}]}";
        TargetDetectResultReport report = mapper.readValue(json, TargetDetectResultReport.class);

        assertEquals(1, report.getObjCnt());
        assertEquals("t-1", report.getObjs().get(0).getTrackerId());
        assertEquals(4, report.getObjs().get(0).getClsId());
        assertEquals(0.3f, report.getObjs().get(0).getBbox().getW());
    }
}
