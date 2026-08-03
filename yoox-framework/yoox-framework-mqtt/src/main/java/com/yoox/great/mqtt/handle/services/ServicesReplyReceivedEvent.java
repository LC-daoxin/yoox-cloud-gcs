package com.yoox.great.mqtt.handle.services;

import com.yoox.great.mqtt.core.CommonTopicRequest;

/**
 * Broadcast after a services_reply message has been decoded.
 *
 * <p>The regular request/reply channel is still used to wake synchronous
 * callers. This event also exposes replies that arrive without a waiting
 * caller, so application services can observe device command results.</p>
 */
public class ServicesReplyReceivedEvent {

    private final String topic;

    private final TopicServicesResponse<ServicesReplyReceiver> response;

    private final CommonTopicRequest<?> request;

    public ServicesReplyReceivedEvent(
            String topic,
            TopicServicesResponse<ServicesReplyReceiver> response,
            CommonTopicRequest<?> request) {
        this.topic = topic;
        this.response = response;
        this.request = request;
    }

    public String getTopic() {
        return topic;
    }

    public TopicServicesResponse<ServicesReplyReceiver> getResponse() {
        return response;
    }

    public CommonTopicRequest<?> getRequest() {
        return request;
    }
}
