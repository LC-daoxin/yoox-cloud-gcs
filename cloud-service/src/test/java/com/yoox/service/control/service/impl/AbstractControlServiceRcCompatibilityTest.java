package com.yoox.service.control.service.impl;

import com.yoox.api.control.AbstractControlService;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.context.exception.CloudSDKException;
import com.yoox.great.mqtt.enums.control.CameraTypeEnum;
import com.yoox.great.mqtt.enums.control.GimbalResetModeEnum;
import com.yoox.great.mqtt.enums.control.LensStorageSettingsEnum;
import com.yoox.great.mqtt.enums.device.CameraModeEnum;
import com.yoox.great.mqtt.handle.services.ServicesPublish;
import com.yoox.great.mqtt.model.config.CloudSDKHandler;
import com.yoox.great.mqtt.model.control.CameraAimRequest;
import com.yoox.great.mqtt.model.control.CameraModeSwitchRequest;
import com.yoox.great.mqtt.model.control.GimbalResetRequest;
import com.yoox.great.mqtt.model.control.PhotoStorageSetRequest;
import com.yoox.great.mqtt.model.control.VideoStorageSetRequest;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AbstractControlServiceRcCompatibilityTest {

    private static final String RC_SN = "test-rc";
    private static final PayloadIndex PAYLOAD_INDEX = new PayloadIndex("10806-0-0");

    @Mock
    private ServicesPublish servicesPublish;

    private AbstractControlService controlService;
    private GatewayManager rcGateway;

    @BeforeEach
    void setUp() {
        SDKControlService target = new SDKControlService();
        ReflectionTestUtils.setField(target, "servicesPublish", servicesPublish);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new CloudSDKHandler());
        controlService = factory.getProxy();
        rcGateway = new GatewayManager(RC_SN, "test-aircraft", GatewayTypeEnum.RC, "1.0.0", null);
    }

    @Test
    void rcPayloadCompatibilityMatrixAllowsSupportedCommandsThroughSpringAop() {
        CameraModeSwitchRequest cameraMode = new CameraModeSwitchRequest()
                .setPayloadIndex(PAYLOAD_INDEX)
                .setCameraMode(CameraModeEnum.PHOTO);
        GimbalResetRequest gimbalReset = new GimbalResetRequest()
                .setPayloadIndex(PAYLOAD_INDEX)
                .setResetMode(GimbalResetModeEnum.RECENTER);
        PhotoStorageSetRequest photoStorage = new PhotoStorageSetRequest()
                .setPayloadIndex(PAYLOAD_INDEX)
                .setPhotoStorageSettings(List.of(LensStorageSettingsEnum.ZOOM));
        VideoStorageSetRequest videoStorage = new VideoStorageSetRequest()
                .setPayloadIndex(PAYLOAD_INDEX)
                .setVideoStorageSettings(List.of(LensStorageSettingsEnum.ZOOM));

        assertDoesNotThrow(() -> controlService.cameraModeSwitch(rcGateway, cameraMode));
        assertDoesNotThrow(() -> controlService.gimbalReset(rcGateway, gimbalReset));
        assertDoesNotThrow(() -> controlService.photoStorageSet(rcGateway, photoStorage));
        assertDoesNotThrow(() -> controlService.videoStorageSet(rcGateway, videoStorage));

        verify(servicesPublish).publish(RC_SN, "camera_mode_switch", cameraMode);
        verify(servicesPublish).publish(RC_SN, "gimbal_reset", gimbalReset);
        verify(servicesPublish).publish(RC_SN, "photo_storage_set", photoStorage);
        verify(servicesPublish).publish(RC_SN, "video_storage_set", videoStorage);
    }

    @Test
    void springAopStillRejectsACommandThatIsExcludedForRc() {
        CameraAimRequest request = new CameraAimRequest()
                .setPayloadIndex(PAYLOAD_INDEX)
                .setCameraType(CameraTypeEnum.ZOOM)
                .setLocked(false)
                .setX(0.5F)
                .setY(0.5F);

        assertThrows(CloudSDKException.class, () -> controlService.cameraAim(rcGateway, request));
        verifyNoInteractions(servicesPublish);
    }
}
