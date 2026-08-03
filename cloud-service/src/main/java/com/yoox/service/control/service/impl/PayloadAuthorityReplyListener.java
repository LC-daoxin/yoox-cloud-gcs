package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.handle.services.ServicesErrorCode;
import com.yoox.great.mqtt.handle.services.ServicesReplyReceivedEvent;
import com.yoox.great.mqtt.handle.services.ServicesReplyReceiver;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.control.PayloadAuthorityGrabRequest;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.control.model.enums.DroneControlMethodEnum;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Observes payload-authority command replies independently from the synchronous
 * HTTP caller and forwards the result to cockpit WebSocket clients.
 */
@Slf4j
@Component
public class PayloadAuthorityReplyListener {

    private static final Pattern SERVICES_REPLY_TOPIC =
            Pattern.compile("^thing/product/([^/]+)/services_reply$");

    @Resource
    private IDeviceService deviceService;

    @Resource
    private IWebSocketMessageService webSocketMessageService;

    @Resource
    private PayloadAuthorityCacheService payloadAuthorityCacheService;

    @EventListener
    public void onServicesReply(ServicesReplyReceivedEvent event) {
        TopicServicesResponse<ServicesReplyReceiver> response = event.getResponse();
        if (!DroneControlMethodEnum.PAYLOAD_AUTHORITY_GRAB.getMethod().equals(response.getMethod())) {
            return;
        }

        Matcher matcher = SERVICES_REPLY_TOPIC.matcher(event.getTopic());
        if (!matcher.matches()) {
            log.warn("Ignoring payload authority reply with unexpected topic: {}", event.getTopic());
            return;
        }

        String gatewaySn = matcher.group(1);
        Optional<String> workspaceId = deviceService.getDeviceBySn(gatewaySn)
                .map(DeviceDTO::getWorkspaceId)
                .filter(StringUtils::hasText);
        if (workspaceId.isEmpty()) {
            log.warn("Unable to publish payload authority reply: gateway {} is not bound to a workspace", gatewaySn);
            return;
        }

        ServicesReplyReceiver data = response.getData();
        ServicesErrorCode result = data == null ? null : data.getResult();
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("gateway_sn", gatewaySn);
        String payloadIndex = payloadIndex(event);
        if (StringUtils.hasText(payloadIndex)) {
            notification.put("payload_index", payloadIndex);
            if (result != null && result.isSuccess()) {
                payloadAuthorityCacheService.confirm(gatewaySn, payloadIndex);
            }
        }
        notification.put("method", response.getMethod());
        notification.put("tid", response.getTid());
        notification.put("bid", response.getBid());
        notification.put("result", result == null ? null : result.getCode());
        notification.put("success", result != null && result.isSuccess());
        notification.put("message", resultMessage(result));
        notification.put("timestamp", response.getTimestamp());

        webSocketMessageService.sendBatch(
                workspaceId.get(),
                UserTypeEnum.WEB.getVal(),
                BizCodeEnum.PAYLOAD_AUTHORITY_GRAB.getCode(),
                notification);
        log.info("Payload authority reply observed for gateway {} payload {}: result={}",
                gatewaySn, payloadIndex, result == null ? null : result.getCode());
    }

    private String payloadIndex(ServicesReplyReceivedEvent event) {
        if (event.getRequest() == null ||
                !(event.getRequest().getData() instanceof PayloadAuthorityGrabRequest)) {
            return null;
        }
        PayloadAuthorityGrabRequest request =
                (PayloadAuthorityGrabRequest) event.getRequest().getData();
        return request.getPayloadIndex() == null ? null : request.getPayloadIndex().toString();
    }

    private String resultMessage(ServicesErrorCode result) {
        if (result == null) {
            return "设备未返回结果";
        }
        if (result.isSuccess()) {
            return "已取得负载控制权";
        }
        try {
            return result.getMessage();
        } catch (RuntimeException exception) {
            return "设备返回错误码 " + result.getCode();
        }
    }
}
