package com.yoox.service.control.service.impl;

import com.yoox.api.wayline.AbstractWaylineService;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.mqtt.handle.services.ServicesPublish;
import com.yoox.great.mqtt.model.config.CloudSDKHandler;
import com.yoox.service.wayline.service.impl.SDKWaylineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AbstractWaylineServiceRcCompatibilityTest {

    private static final String RC_SN = "test-rc";

    @Mock
    private ServicesPublish servicesPublish;

    private AbstractWaylineService waylineService;
    private GatewayManager rcGateway;

    @BeforeEach
    void setUp() {
        SDKWaylineService target = new SDKWaylineService();
        ReflectionTestUtils.setField(target, "servicesPublish", servicesPublish);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new CloudSDKHandler());
        waylineService = factory.getProxy();
        rcGateway = new GatewayManager(RC_SN, "test-aircraft", GatewayTypeEnum.RC, "1.0.0", null);
    }

    @Test
    void rcReturnHomePublishesGatewayServicesCommand() {
        assertDoesNotThrow(() -> waylineService.returnHome(rcGateway));

        verify(servicesPublish).publish(RC_SN, "return_home");
    }

    /**
     * EVO RC 实测（2026-08-12 A/B）：return_home 必须 data={} + device_list 才有回复；
     * data:null 的任何写法都被固件静默丢弃（211001）。
     */
    @Test
    void rcReturnHomeRcSendsEmptyDataWithDeviceList() {
        assertDoesNotThrow(() -> waylineService.returnHomeRc(rcGateway));

        verify(servicesPublish).publish(
                RC_SN, "return_home", Map.of(), List.of(Map.of("sn", "test-aircraft")));
    }

    @Test
    void rcReturnHomeCancelRcSendsEmptyDataWithDeviceList() {
        assertDoesNotThrow(() -> waylineService.returnHomeCancelRc(rcGateway));

        verify(servicesPublish).publish(
                RC_SN, "return_home_cancel", Map.of(), List.of(Map.of("sn", "test-aircraft")));
    }
}
