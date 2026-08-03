package com.yoox.great.mqtt.handle.osd;

import com.yoox.great.context.base.Common;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.exception.CloudSDKException;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.constant.TopicConst;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.model.device.PayloadModelConst;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class OsdRouter {

    private static final Pattern PAYLOAD_INDEX_PATTERN = Pattern.compile("^\\d+-\\d+-\\d+$");

    @Bean
    public IntegrationFlow osdRouterFlow() {
        return IntegrationFlows
                .from(ChannelName.INBOUND_OSD)
                .transform(Message.class, source -> {
                    try {
                        TopicOsdRequest response = Common.getObjectMapper().readValue((byte[]) source.getPayload(), new TypeReference<TopicOsdRequest>() {
                        });
                        String topic = String.valueOf(source.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
                        return response.setFrom(topic.substring((TopicConst.THING_MODEL_PRE + TopicConst.PRODUCT).length(), topic.indexOf(TopicConst.OSD_SUF)));
                    } catch (IOException e) {
                        throw new CloudSDKException(e);
                    }
                }, null).<TopicOsdRequest>handle((response, headers) -> {
                    GatewayManager gateway = SDKManager.getDeviceSDK(response.getGateway());
                    OsdDeviceTypeEnum typeEnum = OsdDeviceTypeEnum.find(gateway.getType(), response.getFrom().equals(response.getGateway()));
                    Map<String, Object> data = (Map<String, Object>) response.getData();
                    if (!typeEnum.isGateway()) {
                        List payloadData = (List) data.getOrDefault(PayloadModelConst.PAYLOAD_KEY, new ArrayList<>());
                        // 部分 Autel 负载型号尚未收录在 DeviceEnum 中，但同样按
                        // type-subtype-position 作为 OSD 节点键。按协议格式识别，避免云台等
                        // 实时数据在转换成强类型 OSD 前被遗漏。
                        data.entrySet().stream()
                                .filter(entry -> PAYLOAD_INDEX_PATTERN.matcher(entry.getKey()).matches())
                                .map(Map.Entry::getValue)
                                .filter(Map.class::isInstance)
                                .forEach(payloadData::add);
                        data.put(PayloadModelConst.PAYLOAD_KEY, payloadData);
                    }
                    return response.setData(Common.getObjectMapper().convertValue(data, typeEnum.getClassType()));
                }).<TopicOsdRequest, OsdDeviceTypeEnum>route(response -> OsdDeviceTypeEnum.find(response.getData().getClass()), mapping -> Arrays.stream(OsdDeviceTypeEnum.values()).forEach(key -> mapping.channelMapping(key, key.getChannelName()))).get();
    }

}
