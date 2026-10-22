package com.yoox.service.manage.service.impl;

import com.yoox.api.device.AbstractDeviceService;
import com.yoox.great.context.base.Common;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.core.consume.MqttReplyHandler;
import com.yoox.great.mqtt.handle.status.TopicStatusRequest;
import com.yoox.great.mqtt.handle.status.TopicStatusResponse;
import com.yoox.great.mqtt.model.device.UpdateTopo;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.messaging.MessageHeaders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MqttReplyHandlerTest {

    @Test
    void overriddenOnlineHandlerReturnsCorrelatedProtocolEnvelope() throws Exception {
        AspectJProxyFactory factory = new AspectJProxyFactory(new DeviceHandler());
        factory.setProxyTargetClass(true);
        factory.addAspect(new MqttReplyHandler());
        AbstractDeviceService service = factory.getProxy();
        TopicStatusRequest<UpdateTopo> request = new TopicStatusRequest<UpdateTopo>()
                .setTid("request-tid").setBid("request-bid")
                .setMethod("update_topo").setTimestamp(1L);
        long before = System.currentTimeMillis();

        TopicStatusResponse<MqttReply> response = service.updateTopoOnline(
                request, new MessageHeaders(Map.of()));

        var json = Common.getObjectMapper().readTree(
                Common.getObjectMapper().writeValueAsBytes(response));
        assertEquals("request-tid", json.path("tid").asText());
        assertEquals("request-bid", json.path("bid").asText());
        assertEquals("update_topo", json.path("method").asText());
        assertTrue(json.path("timestamp").asLong() >= before);
        assertEquals(0, json.path("data").path("result").asInt(-1));
    }

    public static class DeviceHandler extends AbstractDeviceService {
        @Override
        public TopicStatusResponse<MqttReply> updateTopoOnline(
                TopicStatusRequest<UpdateTopo> request, MessageHeaders headers) {
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
        }
    }
}
