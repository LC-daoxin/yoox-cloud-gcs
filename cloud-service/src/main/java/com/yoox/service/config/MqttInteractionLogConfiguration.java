package com.yoox.service.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors raw MQTT traffic to authenticated web clients for the cockpit
 * interaction log. Credentials and tokens are redacted before transmission.
 */
@Configuration
public class MqttInteractionLogConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MqttInteractionLogConfiguration.class);
    private static final String BIZ_CODE = "interaction_log";
    private static final Pattern PRODUCT_TOPIC = Pattern.compile("^thing/product/([^/]+)/(.+)$");
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("password|passwd|token|secret|authorization|credential", Pattern.CASE_INSENSITIVE);
    private final ConcurrentMap<String, String> deviceWorkspaces = new ConcurrentHashMap<>();

    @Resource(name = ChannelName.INBOUND)
    private MessageChannel inboundChannel;

    @Resource(name = ChannelName.OUTBOUND)
    private MessageChannel outboundChannel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private IDeviceService deviceService;

    @Resource
    private IWebSocketMessageService webSocketMessageService;

    @Bean(name = ChannelName.OUTBOUND)
    public static MessageChannel outboundChannel() {
        return new DirectChannel();
    }

    @PostConstruct
    public void registerInterceptors() {
        addInterceptor(inboundChannel, "IN");
        addInterceptor(outboundChannel, "OUT");
    }

    private void addInterceptor(MessageChannel channel, String direction) {
        if (!(channel instanceof AbstractMessageChannel)) {
            log.warn("Unable to attach MQTT interaction logger to channel {}", channel);
            return;
        }
        ((AbstractMessageChannel) channel).addInterceptor(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel ignored) {
                mirror(message, direction);
                return message;
            }
        });
    }

    private void mirror(Message<?> message, String direction) {
        String topic = String.valueOf(Optional.ofNullable(
                        message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC))
                .orElse(message.getHeaders().get(MqttHeaders.TOPIC)));
        Matcher matcher = PRODUCT_TOPIC.matcher(topic);
        if (!matcher.matches()) {
            return;
        }

        String deviceSn = matcher.group(1);
        Optional.ofNullable(deviceWorkspaces.computeIfAbsent(deviceSn, this::findWorkspaceId))
                .filter(StringUtils::hasText)
                .ifPresent(workspaceId -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("transport", "MQTT");
                    data.put("direction", direction);
                    data.put("topic", topic);
                    data.put("summary", describeTopic(matcher.group(2)));
                    data.put("payload", parseAndRedact(message.getPayload()));
                    try {
                        webSocketMessageService.sendBatch(
                                workspaceId, UserTypeEnum.WEB.getVal(), BIZ_CODE, data);
                    } catch (RuntimeException exception) {
                        log.debug("Unable to mirror MQTT interaction {}", topic, exception);
                    }
                });
    }

    private String findWorkspaceId(String deviceSn) {
        return deviceService.getDeviceBySn(deviceSn)
                .map(DeviceDTO::getWorkspaceId)
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    private Object parseAndRedact(Object payload) {
        try {
            JsonNode node;
            if (payload instanceof byte[]) {
                node = objectMapper.readTree((byte[]) payload);
            } else if (payload instanceof String) {
                node = objectMapper.readTree((String) payload);
            } else {
                node = objectMapper.valueToTree(payload);
            }
            redact(node);
            return node;
        } catch (IOException | IllegalArgumentException exception) {
            String text = payload instanceof byte[]
                    ? new String((byte[]) payload, StandardCharsets.UTF_8)
                    : String.valueOf(payload);
            return text.length() > 20_000 ? text.substring(0, 20_000) + "…" : text;
        }
    }

    private void redact(JsonNode node) {
        if (node instanceof ObjectNode) {
            ObjectNode object = (ObjectNode) node;
            object.fieldNames().forEachRemaining(field -> {
                if (SENSITIVE_KEY.matcher(field).find()) {
                    object.put(field, "***");
                } else {
                    redact(object.get(field));
                }
            });
        } else if (node instanceof ArrayNode) {
            node.forEach(this::redact);
        }
    }

    private String describeTopic(String suffix) {
        if (suffix.equals("services") || suffix.endsWith("/services")) return "云服务向设备下发服务指令";
        if (suffix.equals("services_reply") || suffix.endsWith("/services_reply")) return "设备回复服务指令";
        if (suffix.equals("state") || suffix.endsWith("/state")) return "设备状态变化上报";
        if (suffix.equals("osd") || suffix.endsWith("/osd")) return "设备 0.5Hz 定频数据上报";
        if (suffix.equals("events") || suffix.endsWith("/events")) return "设备事件上报";
        if (suffix.equals("events_reply") || suffix.endsWith("/events_reply")) return "云服务回复设备事件";
        if (suffix.equals("requests") || suffix.endsWith("/requests")) return "设备向云服务发起请求";
        if (suffix.equals("requests_reply") || suffix.endsWith("/requests_reply")) return "云服务回复设备请求";
        if (suffix.contains("/drc/")) return "DRC 实时控制数据";
        return "MQTT 交互";
    }
}
