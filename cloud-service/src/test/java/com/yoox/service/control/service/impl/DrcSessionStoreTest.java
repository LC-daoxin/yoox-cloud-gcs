package com.yoox.service.control.service.impl;

import com.yoox.great.redis.RedisConst;
import com.yoox.service.control.model.dto.DrcSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class DrcSessionStoreTest {

    private static final String GATEWAY_SN = "test-rc";
    private static final String WORKSPACE_ID = "workspace";
    private static final String USER_ID = "user";
    private static final String CLIENT_ID = "browser-client";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, String, String> sessionHashOperations;

    @Mock
    private HashOperations<String, Object, Object> aclHashOperations;

    @InjectMocks
    private DrcSessionStore sessionStore;

    @BeforeEach
    void setUpOperations() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.<String, String>opsForHash())
                .thenReturn(sessionHashOperations);
    }

    @Test
    void browserClientCannotBeClaimedByAnotherOwner() {
        when(valueOperations.get("drc:owner:" + CLIENT_ID))
                .thenReturn("other-workspace\nother-user");

        assertThrows(SecurityException.class,
                () -> sessionStore.assertBrowserClientOwner(
                        WORKSPACE_ID, USER_ID, CLIENT_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gatewayLeaseUsesAtomicSetIfAbsentForSingleOwner() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L, 0L);

        assertTrue(sessionStore.acquireSession(session("generation-one", CLIENT_ID)));
        assertFalse(sessionStore.acquireSession(session("generation-two", "other-client")));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stateTransitionAtomicallyChecksGenerationAndRefreshesLeaseKeys() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        DrcSession session = session("generation", CLIENT_ID);

        assertTrue(sessionStore.markActive(session));

        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                script.capture(), keys.capture(),
                eq("generation"), eq("ENTERING"), eq("ACTIVE"),
                eq(RedisConst.DRC_MODE_ALIVE_SECOND.toString()));
        assertTrue(script.getValue().getScriptAsString().contains(
                "redis.call('get', KEYS[1]) ~= ARGV[1]"));
        assertTrue(script.getValue().getScriptAsString().contains(
                "redis.call('get', KEYS[2]) ~= ARGV[2]"));
        assertTrue(script.getValue().getScriptAsString().contains("redis.call('expire'"));
        assertEquals(RedisConst.DRC_PREFIX + GATEWAY_SN, keys.getValue().get(0));
        assertEquals("drc:state:generation", keys.getValue().get(1));
        assertTrue(keys.getValue().contains(RedisConst.MQTT_ACL_PREFIX + CLIENT_ID));
        assertTrue(keys.getValue().contains(RedisConst.MQTT_ACL_PREFIX + "device-client"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activeLeaseCanBeAtomicallyReboundToFreshSameOwnerBrowserClient() {
        String replacementClientId = "replacement-browser";
        when(valueOperations.get("drc:owner:" + replacementClientId))
                .thenReturn(WORKSPACE_ID + "\n" + USER_ID);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        DrcSession session = session("generation", CLIENT_ID);

        assertTrue(sessionStore.rebindBrowserClient(session, replacementClientId));

        assertEquals(replacementClientId, session.getBrowserClientId());
        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                script.capture(), keys.capture(),
                eq("generation"), eq(CLIENT_ID), eq(replacementClientId),
                eq("generation\n" + CLIENT_ID),
                eq("generation\n" + replacementClientId),
                eq(WORKSPACE_ID + "\n" + USER_ID),
                eq(RedisConst.DRC_MODE_ALIVE_SECOND.toString()),
                eq("browser_client_id"));
        assertTrue(script.getValue().getScriptAsString().contains("redis.call('del', KEYS[9])"));
        assertEquals("drc:client-session:" + CLIENT_ID, keys.getValue().get(3));
        assertEquals("drc:client-session:" + replacementClientId, keys.getValue().get(4));
        assertEquals(RedisConst.MQTT_ACL_PREFIX + CLIENT_ID, keys.getValue().get(8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exitingStateIsReturnedAsRetryInsteadOfSuccessNoOp() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString()))
                .thenReturn(4L);

        assertEquals(DrcSessionStore.ExitPreparation.RETRY_EXITING,
                sessionStore.prepareExit(session("generation", CLIENT_ID)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void disconnectCleanupAtomicallyClaimsTheCurrentGeneration() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString()))
                .thenReturn(1L, 2L, 0L);
        DrcSession session = session("generation", CLIENT_ID);

        assertTrue(sessionStore.claimEventCleanup(session));
        assertTrue(sessionStore.claimEventCleanup(session));
        assertFalse(sessionStore.claimEventCleanup(session));

        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate, org.mockito.Mockito.times(3)).execute(
                script.capture(), keys.capture(),
                eq("generation"), eq(RedisConst.DRC_MODE_ALIVE_SECOND.toString()));
        String lua = script.getAllValues().get(0).getScriptAsString();
        assertTrue(lua.contains("redis.call('get', KEYS[1]) ~= ARGV[1]"));
        assertTrue(lua.contains("state == 'EXITING'"));
        assertTrue(lua.contains("'EVENT_CLEANING'"));
        assertTrue(lua.contains("redis.call('expire'"));
        assertEquals(RedisConst.DRC_PREFIX + GATEWAY_SN,
                keys.getAllValues().get(0).get(0));
        assertEquals("drc:state:generation", keys.getAllValues().get(0).get(1));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void httpExitCannotTakeOverEventCleanupGeneration() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString()))
                .thenReturn(0L);

        assertEquals(DrcSessionStore.ExitPreparation.REJECTED,
                sessionStore.prepareExit(session("generation", CLIENT_ID)));

        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                script.capture(), any(List.class),
                eq("generation"), eq(RedisConst.DRC_MODE_ALIVE_SECOND.toString()));
        assertTrue(script.getValue().getScriptAsString().contains(
                "state == 'EVENT_CLEANING' then return 0"));
    }

    @Test
    void missingMetadataCanBeRebuiltOnlyFromMatchingOwnerMarkers() {
        String generation = "generation";
        when(valueOperations.get("drc:owner:" + CLIENT_ID))
                .thenReturn(WORKSPACE_ID + "\n" + USER_ID);
        when(valueOperations.get(RedisConst.DRC_PREFIX + GATEWAY_SN))
                .thenReturn(generation);
        when(valueOperations.get("drc:client-session:" + CLIENT_ID))
                .thenReturn(generation);
        when(valueOperations.get("drc:lease-owner:" + GATEWAY_SN))
                .thenReturn(generation + "\n" + WORKSPACE_ID + "\n" + USER_ID);
        when(valueOperations.get("drc:lease-browser:" + GATEWAY_SN))
                .thenReturn(generation + "\n" + CLIENT_ID);
        when(valueOperations.get("drc:paused-job:" + generation)).thenReturn("job-id");
        when(valueOperations.get("drc:device-watermark:" + generation)).thenReturn("250");
        when(sessionHashOperations.entries("drc:session:" + generation))
                .thenReturn(Map.of());

        DrcSession recovered = sessionStore.recoverSessionForOwner(
                GATEWAY_SN, WORKSPACE_ID, USER_ID, CLIENT_ID).orElse(null);

        assertNotNull(recovered);
        assertEquals(GATEWAY_SN + "-" + generation, recovered.getDeviceClientId());
        assertEquals("job-id", recovered.getPausedJobId());
        assertEquals(250L, recovered.getDeviceTimestampWatermark());
    }

    @Test
    void deviceEventCanRecoverMissingPrimaryMetadataFromLeaseMarkers() {
        String generation = "generation";
        when(valueOperations.get(RedisConst.DRC_PREFIX + GATEWAY_SN))
                .thenReturn(generation);
        when(valueOperations.get("drc:lease-owner:" + GATEWAY_SN))
                .thenReturn(generation + "\n" + WORKSPACE_ID + "\n" + USER_ID);
        when(valueOperations.get("drc:lease-browser:" + GATEWAY_SN))
                .thenReturn(generation + "\n" + CLIENT_ID);
        when(valueOperations.get("drc:paused-job:" + generation)).thenReturn("job-id");
        when(valueOperations.get("drc:device-watermark:" + generation)).thenReturn("250");
        when(sessionHashOperations.entries("drc:session:" + generation))
                .thenReturn(Map.of());

        DrcSession recovered = sessionStore.getSessionForEvent(GATEWAY_SN).orElseThrow();

        assertEquals(GATEWAY_SN, recovered.getGatewaySn());
        assertEquals(WORKSPACE_ID, recovered.getWorkspaceId());
        assertEquals(USER_ID, recovered.getUserId());
        assertEquals(CLIENT_ID, recovered.getBrowserClientId());
        assertEquals(GATEWAY_SN + "-" + generation, recovered.getDeviceClientId());
        assertEquals("job-id", recovered.getPausedJobId());
        assertEquals(250L, recovered.getDeviceTimestampWatermark());
    }

    @Test
    void deviceEventDoesNotAcknowledgeAnUnrecoverableActiveLease() {
        String generation = "generation";
        when(valueOperations.get(RedisConst.DRC_PREFIX + GATEWAY_SN))
                .thenReturn(generation);
        when(sessionHashOperations.entries("drc:session:" + generation))
                .thenReturn(Map.of());

        assertThrows(IllegalStateException.class,
                () -> sessionStore.getSessionForEvent(GATEWAY_SN));
    }

    @Test
    void deviceEventRejectsMetadataBelongingToAnotherGateway() {
        String generation = "generation";
        when(valueOperations.get(RedisConst.DRC_PREFIX + GATEWAY_SN))
                .thenReturn(generation);
        when(sessionHashOperations.entries("drc:session:" + generation))
                .thenReturn(Map.of(
                        "gateway_sn", "other-gateway",
                        "workspace_id", WORKSPACE_ID,
                        "user_id", USER_ID,
                        "browser_client_id", CLIENT_ID,
                        "device_client_id", "device-client",
                        "generation", generation,
                        "created_at", "100"));

        assertThrows(IllegalStateException.class,
                () -> sessionStore.getSessionForEvent(GATEWAY_SN));
    }

    @Test
    void recoveryRejectsMismatchedGatewayOwnerMarker() {
        String generation = "generation";
        when(valueOperations.get("drc:owner:" + CLIENT_ID))
                .thenReturn(WORKSPACE_ID + "\n" + USER_ID);
        when(valueOperations.get(RedisConst.DRC_PREFIX + GATEWAY_SN))
                .thenReturn(generation);
        when(valueOperations.get("drc:client-session:" + CLIENT_ID))
                .thenReturn(generation);
        when(valueOperations.get("drc:lease-owner:" + GATEWAY_SN))
                .thenReturn(generation + "\nother-workspace\nother-user");

        assertThrows(SecurityException.class, () -> sessionStore.recoverSessionForOwner(
                GATEWAY_SN, WORKSPACE_ID, USER_ID, CLIENT_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseRevokesBrowserAndDeviceAclsBeforeLease() {
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class), anyString()))
                .thenReturn(1L);
        DrcSession session = session("generation", CLIENT_ID);

        assertTrue(sessionStore.releaseSession(session));

        verify(stringRedisTemplate).delete(RedisConst.MQTT_ACL_PREFIX + CLIENT_ID);
        verify(stringRedisTemplate).delete(RedisConst.MQTT_ACL_PREFIX + "device-client");
        verify(stringRedisTemplate).expire(
                "drc:owner:" + CLIENT_ID,
                RedisConst.DRC_MODE_ALIVE_SECOND,
                TimeUnit.SECONDS);
    }

    @Test
    void drcAclUsesRawEmqxActionsInsteadOfJsonEncodedStrings() {
        when(stringRedisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        sessionStore.grantUserTopics(
                CLIENT_ID, "thing/device/drc/down", "thing/device/drc/up");

        verify(sessionHashOperations).put(
                RedisConst.MQTT_ACL_PREFIX + CLIENT_ID,
                "thing/device/drc/down", "publish");
        verify(sessionHashOperations).put(
                RedisConst.MQTT_ACL_PREFIX + CLIENT_ID,
                "thing/device/drc/up", "subscribe");

        sessionStore.grantUserSubscribeTopic(
                CLIENT_ID, "thing/device/services_reply");
        verify(sessionHashOperations).put(
                RedisConst.MQTT_ACL_PREFIX + CLIENT_ID,
                "thing/device/services_reply", "subscribe");
    }

    @Test
    void controlTopicSerialIsPersistedAndRestoredWithSessionMetadata() {
        DrcSession session = session("generation", CLIENT_ID);
        session.setControlTopicSn("aircraft-sn");

        sessionStore.saveSession(session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(sessionHashOperations).putAll(eq("drc:session:generation"), values.capture());
        assertEquals("aircraft-sn", values.getValue().get("control_topic_sn"));

        when(sessionHashOperations.entries("drc:session:generation"))
                .thenReturn(values.getValue());
        DrcSession restored = sessionStore.getSessionByGeneration("generation").orElseThrow();
        assertEquals("aircraft-sn", restored.getControlTopicSn());
    }

    private DrcSession session(String generation, String clientId) {
        return DrcSession.builder()
                .gatewaySn(GATEWAY_SN)
                .workspaceId(WORKSPACE_ID)
                .userId(USER_ID)
                .browserClientId(clientId)
                .deviceClientId("device-client")
                .generation(generation)
                .createdAt(100L)
                .build();
    }
}
