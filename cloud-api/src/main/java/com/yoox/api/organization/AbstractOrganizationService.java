package com.yoox.api.organization;

import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.model.organization.*;
import com.yoox.great.mqtt.handle.requests.TopicRequestsRequest;
import com.yoox.great.mqtt.handle.requests.TopicRequestsResponse;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHeaders;

public abstract class AbstractOrganizationService {

    @ServiceActivator(inputChannel = ChannelName.INBOUND_REQUESTS_AIRPORT_BIND_STATUS, outputChannel = ChannelName.OUTBOUND_REQUESTS)
    public TopicRequestsResponse<MqttReply<AirportBindStatusResponse>> airportBindStatus(
            TopicRequestsRequest<AirportBindStatusRequest> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("airportBindStatus not implemented");
    }

    @ServiceActivator(inputChannel = ChannelName.INBOUND_REQUESTS_AIRPORT_ORGANIZATION_GET, outputChannel = ChannelName.OUTBOUND_REQUESTS)
    public TopicRequestsResponse<MqttReply<AirportOrganizationGetResponse>> airportOrganizationGet(
            TopicRequestsRequest<AirportOrganizationGetRequest> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("airportOrganizationGet not implemented");
    }

    @ServiceActivator(inputChannel = ChannelName.INBOUND_REQUESTS_AIRPORT_ORGANIZATION_BIND, outputChannel = ChannelName.OUTBOUND_REQUESTS)
    public TopicRequestsResponse<MqttReply<AirportOrganizationBindResponse>> airportOrganizationBind(
            TopicRequestsRequest<AirportOrganizationBindRequest> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("airportOrganizationBind not implemented");
    }
}
