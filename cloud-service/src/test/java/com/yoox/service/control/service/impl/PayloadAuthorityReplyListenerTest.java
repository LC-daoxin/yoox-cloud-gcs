package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.handle.services.ServicesErrorCode;
import com.yoox.great.mqtt.handle.services.ServicesReplyReceivedEvent;
import com.yoox.great.mqtt.handle.services.ServicesReplyReceiver;
import com.yoox.great.mqtt.handle.services.TopicServicesRequest;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.control.PayloadAuthorityGrabRequest;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.control.model.enums.DroneControlMethodEnum;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayloadAuthorityReplyListenerTest {

    private static final String GATEWAY_SN = "test-rc";
    private static final String PAYLOAD_INDEX = "10806-0-0";
    private static final String WORKSPACE_ID = "workspace-a";

    @Mock
    private IDeviceService deviceService;

    @Mock
    private IWebSocketMessageService webSocketMessageService;

    @Mock
    private PayloadAuthorityCacheService payloadAuthorityCacheService;

    @InjectMocks
    private PayloadAuthorityReplyListener listener;

    @Test
    void lateSuccessfulReplyUpdatesCacheAndNotifiesWorkspace() {
        when(deviceService.getDeviceBySn(GATEWAY_SN)).thenReturn(Optional.of(
                DeviceDTO.builder().deviceSn(GATEWAY_SN).workspaceId(WORKSPACE_ID).build()));
        when(payloadAuthorityCacheService.confirm(GATEWAY_SN, PAYLOAD_INDEX)).thenReturn(true);

        listener.onServicesReply(event(0));

        verify(payloadAuthorityCacheService).confirm(GATEWAY_SN, PAYLOAD_INDEX);
        ArgumentCaptor<Object> dataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(webSocketMessageService).sendBatch(
                eq(WORKSPACE_ID),
                eq(UserTypeEnum.WEB.getVal()),
                eq(BizCodeEnum.PAYLOAD_AUTHORITY_GRAB.getCode()),
                dataCaptor.capture());
        Map<?, ?> notification = (Map<?, ?>) dataCaptor.getValue();
        assertEquals(PAYLOAD_INDEX, notification.get("payload_index"));
        assertTrue((Boolean) notification.get("success"));
    }

    @Test
    void failedReplyNeverGrantsCachedAuthority() {
        when(deviceService.getDeviceBySn(GATEWAY_SN)).thenReturn(Optional.of(
                DeviceDTO.builder().deviceSn(GATEWAY_SN).workspaceId(WORKSPACE_ID).build()));

        listener.onServicesReply(event(1));

        verify(payloadAuthorityCacheService, never()).confirm(anyString(), anyString());
    }

    private ServicesReplyReceivedEvent event(int resultCode) {
        PayloadAuthorityGrabRequest requestData = new PayloadAuthorityGrabRequest()
                .setPayloadIndex(new PayloadIndex(PAYLOAD_INDEX));
        TopicServicesRequest<PayloadAuthorityGrabRequest> request =
                new TopicServicesRequest<PayloadAuthorityGrabRequest>()
                        .setMethod(DroneControlMethodEnum.PAYLOAD_AUTHORITY_GRAB.getMethod())
                        .setTid("tid-1")
                        .setBid("bid-1")
                        .setTimestamp(1L)
                        .setData(requestData);
        TopicServicesResponse<ServicesReplyReceiver> response =
                new TopicServicesResponse<ServicesReplyReceiver>()
                        .setMethod(DroneControlMethodEnum.PAYLOAD_AUTHORITY_GRAB.getMethod())
                        .setTid("tid-1")
                        .setBid("bid-1")
                        .setTimestamp(2L)
                        .setData(new ServicesReplyReceiver<>().setResult(new ServicesErrorCode(resultCode)));
        return new ServicesReplyReceivedEvent(
                "thing/product/" + GATEWAY_SN + "/services_reply",
                response,
                request);
    }
}
