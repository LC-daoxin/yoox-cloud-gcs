package com.yoox.great.mqtt.autoconfiguration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.yoox.great.context.utils.JwtUtil;
import com.yoox.great.mqtt.enums.base.MqttProtocolEnum;
import com.yoox.great.mqtt.enums.base.MqttUseEnum;
import com.yoox.great.mqtt.property.DrcModeMqttBroker;
import com.yoox.great.mqtt.property.MqttClientOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MqttPropertyConfigurationTest {

    private static final String CLIENT_ID = "bound-client";
    private static final String USERNAME = "bound-user";

    @BeforeEach
    void configureDrcBroker() {
        MqttClientOptions options = new MqttClientOptions();
        options.setProtocol(MqttProtocolEnum.WS);
        options.setHost("localhost");
        options.setPort(8083);
        new MqttPropertyConfiguration().setMqtt(Map.of(MqttUseEnum.DRC, options));
        JwtUtil.algorithm = Algorithm.HMAC256("unit-test-secret");
    }

    @Test
    void reservedIdentityClaimsCannotBeOverriddenByCaller() {
        DrcModeMqttBroker broker = MqttPropertyConfiguration.getMqttBrokerWithDrc(
                CLIENT_ID,
                USERNAME,
                60L,
                Map.of(
                        "username", "attacker",
                        "clientid", "attacker-client",
                        "acl", Map.of("pub", new String[]{"thing/test"})));

        DecodedJWT jwt = JWT.require(JwtUtil.algorithm).build().verify(broker.getPassword());

        assertEquals(USERNAME, broker.getUsername());
        assertEquals(CLIENT_ID, broker.getClientId());
        assertEquals(USERNAME, jwt.getClaim("username").asString());
        assertEquals(CLIENT_ID, jwt.getClaim("clientid").asString());
        assertNotNull(jwt.getClaim("acl").asMap());
    }

    @Test
    void identityClaimsArePresentWhenNoAdditionalClaimsAreProvided() {
        DrcModeMqttBroker broker = MqttPropertyConfiguration.getMqttBrokerWithDrc(
                CLIENT_ID, USERNAME, 60L, null);

        DecodedJWT jwt = JWT.require(JwtUtil.algorithm).build().verify(broker.getPassword());

        assertEquals(USERNAME, jwt.getClaim("username").asString());
        assertEquals(CLIENT_ID, jwt.getClaim("clientid").asString());
    }

    @Test
    void blankBrokerIdentityIsRejectedBeforeIssuingToken() {
        assertThrows(IllegalArgumentException.class,
                () -> MqttPropertyConfiguration.getMqttBrokerWithDrc(
                        " ", USERNAME, 60L, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> MqttPropertyConfiguration.getMqttBrokerWithDrc(
                        CLIENT_ID, " ", 60L, Map.of()));
    }
}
