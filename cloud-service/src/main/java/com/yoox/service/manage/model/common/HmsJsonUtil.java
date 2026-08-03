package com.yoox.service.manage.model.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class HmsJsonUtil {

    private static ObjectMapper mapper;

    @Autowired
    public void setMapper(ObjectMapper mapper) {
        HmsJsonUtil.mapper = mapper;
    }

    private static JsonNode nodes;

    private static Map<String, JsonNode> normalizedNodes = Map.of();

    HmsJsonUtil(){

    }

    @PostConstruct
    void loadJsonFile() {
        try (InputStream inputStream = new ClassPathResource("hms.json").getInputStream()){
            nodes = mapper.readTree(inputStream);
            Map<String, JsonNode> aliases = new HashMap<>();
            nodes.fields().forEachRemaining(entry ->
                    aliases.put(normalize(entry.getKey()), entry.getValue()));
            normalizedNodes = Map.copyOf(aliases);
        } catch (IOException e) {
            log.error("hms.json failed to load.", e);
        }
    }

    public static HmsMessage get(String key) {
        if (mapper == null || nodes == null || key == null) {
            return new HmsMessage();
        }
        JsonNode node = nodes.get(key);
        if (node == null) {
            String normalizedKey = normalize(key);
            node = normalizedNodes.get(normalizedKey);
            if (node == null && normalizedKey.endsWith("_in_the_sky")) {
                node = normalizedNodes.get(normalizedKey.substring(
                        0, normalizedKey.length() - "_in_the_sky".length()));
            }
        }
        return node == null ? new HmsMessage() : mapper.convertValue(node, HmsMessage.class);
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT);
    }
}
