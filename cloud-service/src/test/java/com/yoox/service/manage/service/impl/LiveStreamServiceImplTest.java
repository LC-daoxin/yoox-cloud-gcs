package com.yoox.service.manage.service.impl;

import com.yoox.api.livestream.AbstractLivestreamService;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.enums.livestream.LensChangeVideoTypeEnum;
import com.yoox.great.mqtt.enums.livestream.UrlTypeEnum;
import com.yoox.great.mqtt.enums.livestream.VideoQualityEnum;
import com.yoox.great.mqtt.handle.services.ServicesErrorCode;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import com.yoox.great.mqtt.model.device.VideoId;
import com.yoox.great.mqtt.model.livestream.LiveLensChangeRequest;
import com.yoox.great.mqtt.model.livestream.LiveStartPushRequest3;
import com.yoox.great.mqtt.model.livestream.LiveStopPushRequest;
import com.yoox.great.mqtt.model.livestream.LivestreamRtspUrl;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.model.dto.LiveDTO;
import com.yoox.service.manage.model.dto.LiveStreamProperty;
import com.yoox.service.manage.model.dto.LiveTypeDTO;
import com.yoox.service.manage.service.ICapacityCameraService;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.manage.service.IWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveStreamServiceImplTest {

    private static final String GATEWAY_SN = "test-rc";
    private static final String AIRCRAFT_SN = "test-aircraft";
    private static final String PAYLOAD_INDEX = "10806-0-0";
    private static final String SDK_VIDEO_ID =
            AIRCRAFT_SN + "/" + PAYLOAD_INDEX + "/normal-0";

    @Mock
    private ICapacityCameraService capacityCameraService;

    @Mock
    private IDeviceService deviceService;

    @Mock
    private IWorkspaceService workspaceService;

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private AbstractLivestreamService abstractLivestreamService;

    @InjectMocks
    private LiveStreamServiceImpl liveStreamService;

    @BeforeEach
    void setUp() {
        SDKManager.registerDevice(
                GATEWAY_SN, AIRCRAFT_SN, GatewayTypeEnum.RC, "1.0.0", null);
        new LiveStreamProperty().setRtsp(new LivestreamRtspUrl()
                .setUrl("rtsp://publisher:secret@media.example:8554/")
                .setPayUrl("/webrtc/%s/whep")
                .setUsername("publisher")
                .setPassword("secret")
                .setPort(8554));

        lenient().when(deviceService.getDeviceBySn(AIRCRAFT_SN)).thenReturn(Optional.of(
                DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        lenient().when(deviceService.getDevicesByParams(any())).thenReturn(List.of(
                DeviceDTO.builder().deviceSn(GATEWAY_SN).build()));
    }

    @AfterEach
    void tearDown() {
        SDKManager.logoutDevice(GATEWAY_SN);
    }

    @Test
    void liveStartUsesFirmwareCompatibleHyphenVideoId() {
        when(abstractLivestreamService.liveStartPush3(
                any(GatewayManager.class), any(LiveStartPushRequest3.class)))
                .thenReturn(successfulStartReply());
        LiveTypeDTO request = liveRequest();
        request.setUrl("rtsp://publisher:secret@media.example:8554/test-stream");

        HttpResultResponse response = liveStreamService.liveStart(request);

        ArgumentCaptor<LiveStartPushRequest3> command =
                ArgumentCaptor.forClass(LiveStartPushRequest3.class);
        verify(abstractLivestreamService).liveStartPush3(
                any(GatewayManager.class), command.capture());
        assertEquals(AIRCRAFT_SN + "-" + PAYLOAD_INDEX,
                command.getValue().getVideo_id());
        assertEquals(Boolean.TRUE, ((LiveDTO) response.getData()).getStartedByRequest());
    }

    @Test
    void liveStartRejectsRtmpInRtspOnlyRuntime() {
        LiveTypeDTO request = liveRequest();
        request.setUrlType(UrlTypeEnum.RTMP);

        HttpResultResponse response = liveStreamService.liveStart(request);

        assertEquals(13013, response.getCode());
        verifyNoInteractions(abstractLivestreamService);
    }

    @Test
    void stopAndLensChangeKeepSdkSlashVideoId() {
        when(abstractLivestreamService.liveStopPush(
                any(GatewayManager.class), any(LiveStopPushRequest.class)))
                .thenReturn(successfulCommandReply());
        when(abstractLivestreamService.liveLensChange(
                any(GatewayManager.class), any(LiveLensChangeRequest.class)))
                .thenReturn(successfulCommandReply());
        LiveTypeDTO request = liveRequest();

        liveStreamService.liveStop(request.getVideoId());
        liveStreamService.liveLensChange(request);

        ArgumentCaptor<LiveStopPushRequest> stopCommand =
                ArgumentCaptor.forClass(LiveStopPushRequest.class);
        verify(abstractLivestreamService).liveStopPush(
                any(GatewayManager.class), stopCommand.capture());
        assertEquals(SDK_VIDEO_ID, stopCommand.getValue().getVideoId().toString());

        ArgumentCaptor<LiveLensChangeRequest> lensCommand =
                ArgumentCaptor.forClass(LiveLensChangeRequest.class);
        verify(abstractLivestreamService).liveLensChange(
                any(GatewayManager.class), lensCommand.capture());
        assertEquals(SDK_VIDEO_ID, lensCommand.getValue().getVideoId().toString());
    }

    private LiveTypeDTO liveRequest() {
        VideoId videoId = new VideoId()
                .setDroneSn(AIRCRAFT_SN)
                .setPayloadIndex(new PayloadIndex(PAYLOAD_INDEX));
        assertEquals(SDK_VIDEO_ID, videoId.toString());

        LiveTypeDTO request = new LiveTypeDTO();
        request.setUrlType(UrlTypeEnum.RTSP);
        request.setVideoId(videoId);
        request.setVideoQuality(VideoQualityEnum.HIGH_DEFINITION);
        request.setVideoType(LensChangeVideoTypeEnum.ZOOM);
        return request;
    }

    private TopicServicesResponse<ServicesReplyData<String>> successfulStartReply() {
        return new TopicServicesResponse<ServicesReplyData<String>>()
                .setData(new ServicesReplyData<String>()
                        .setResult(new ServicesErrorCode(0)));
    }

    private TopicServicesResponse<ServicesReplyData> successfulCommandReply() {
        return new TopicServicesResponse<ServicesReplyData>()
                .setData(new ServicesReplyData<>()
                        .setResult(new ServicesErrorCode(0)));
    }
}
