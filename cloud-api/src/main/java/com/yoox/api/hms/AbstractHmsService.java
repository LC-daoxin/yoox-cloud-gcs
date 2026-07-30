package com.yoox.api.hms;

import com.yoox.great.mqtt.enums.hms.Hms;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHeaders;

public abstract class AbstractHmsService {

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_HMS)
    public void hms(TopicEventsRequest<Hms> response, MessageHeaders headers) {
        throw new UnsupportedOperationException("hms not implemented");
    }

}
