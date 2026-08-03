package com.yoox.great.mqtt.core.subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class MqttTopicServiceImpl implements IMqttTopicService {

    private static final Logger log = LoggerFactory.getLogger(MqttTopicServiceImpl.class);

    @Resource
    private MqttPahoMessageDrivenChannelAdapter adapter;

    @Override
    public void subscribe(String... topics) {
        Set<String> topicSet = new HashSet<>(Arrays.asList(getSubscribedTopic()));
        for (String topic : topics) {
            if (isCovered(topic, topicSet)) {
                continue;
            }
            subscribe(topic, 1);
            topicSet.add(topic);
        }
    }

    @Override
    public void subscribe(String topic, int qos) {
        Set<String> topicSet = new HashSet<>(Arrays.asList(getSubscribedTopic()));
        if (isCovered(topic, topicSet)) {
            return;
        }
        log.debug("subscribe topic: {}", topic);
        adapter.addTopic(topic, qos);
    }

    @Override
    public void unsubscribe(String... topics) {
        Set<String> subscribed = new HashSet<>(Arrays.asList(getSubscribedTopic()));
        String[] removable = Arrays.stream(topics)
                .filter(subscribed::contains)
                .toArray(String[]::new);
        if (removable.length == 0) {
            return;
        }
        log.debug("unsubscribe topic: {}", Arrays.toString(removable));
        adapter.removeTopic(removable);
    }

    public String[] getSubscribedTopic() {
        return adapter.getTopic();
    }

    private boolean isCovered(String topic, Set<String> subscriptions) {
        return subscriptions.stream().anyMatch(subscription -> matches(subscription, topic));
    }

    private boolean matches(String subscription, String topic) {
        String[] filterLevels = subscription.split("/", -1);
        String[] topicLevels = topic.split("/", -1);
        for (int index = 0; index < filterLevels.length; index++) {
            String filter = filterLevels[index];
            if ("#".equals(filter)) {
                return index == filterLevels.length - 1;
            }
            if (index >= topicLevels.length) {
                return false;
            }
            if (!"+".equals(filter) && !filter.equals(topicLevels[index])) {
                return false;
            }
        }
        return filterLevels.length == topicLevels.length;
    }
}
