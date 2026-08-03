package com.yoox.service.control.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.context.base.Common;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointFlightTaskStoreTest {

    private static final String GATEWAY_SN = "test-gateway";
    private static final String REDIS_KEY = "control:point-flight:" + GATEWAY_SN;
    private static final String ACTIVE_KEY = "control:point-flight:active:" + GATEWAY_SN;
    private static final String TTL_SECONDS = Long.toString(TimeUnit.HOURS.toSeconds(24));

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Spy
    private ObjectMapper objectMapper = Common.getObjectMapper();

    @InjectMocks
    private PointFlightTaskStore taskStore;

    private final AtomicReference<String> storedJson = new AtomicReference<>();

    @BeforeEach
    void mockRedisValueStorage() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(REDIS_KEY)).thenAnswer(invocation -> storedJson.get());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pendingGenerationIsClaimedAtomicallyAndAdvancesThePreviousBaseline() throws Exception {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1L);

        assertTrue(taskStore.tryRecordPending(GATEWAY_SN, "flyto", "flyto-1"));

        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> stateJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> now = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).execute(
                script.capture(), keys.capture(), stateJson.capture(),
                eq("flyto\nflyto-1"), now.capture(), eq(TTL_SECONDS));
        assertEquals(List.of(REDIS_KEY, ACTIVE_KEY), keys.getValue());
        assertTrue(script.getValue().getScriptAsString().contains(
                "redis.call('exists', KEYS[2]) == 1"));
        assertTrue(script.getValue().getScriptAsString().contains(
                "redis.call('set', KEYS[1]"));
        assertTrue(script.getValue().getScriptAsString().contains(
                "redis.call('set', KEYS[2]"));
        assertTrue(script.getValue().getScriptAsString().contains(
                "previousUpdated >= nextUpdated"));
        assertTrue(script.getValue().getScriptAsString().contains(
                "nextUpdated = previousUpdated + 1"));

        Map<String, Object> state = objectMapper.readValue(
                stateJson.getValue(), new TypeReference<Map<String, Object>>() { });
        assertEquals("flyto-1", state.get("fly_to_id"));
        assertEquals("command_pending", state.get("status"));
        assertEquals(Boolean.TRUE, state.get("active"));
        assertEquals(Long.parseLong(now.getValue()),
                ((Number) state.get("updated_at")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyOneConcurrentGenerationWinsTheRedisClaim() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1L, 0L);

        assertTrue(taskStore.tryRecordPending(GATEWAY_SN, "flyto", "flyto-1"));
        assertFalse(taskStore.tryRecordPending(GATEWAY_SN, "flyto", "flyto-2"));

        verify(stringRedisTemplate, times(2)).execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), eq(TTL_SECONDS));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void progressUsesGenerationAndTimestampCasAndCannotReverseTerminalState() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        assertTrue(taskStore.recordProgressIfCurrent(GATEWAY_SN, "flyto", Map.of(
                "fly_to_id", "flyto-1",
                "status", "wayline_progress",
                "timestamp", 200L,
                "remaining_distance", 25.5)));

        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> stateJson = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).execute(
                script.capture(), keys.capture(), eq("flyto\nflyto-1"),
                stateJson.capture(), eq("200"), eq("1"), eq(TTL_SECONDS));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("if claim ~= ARGV[1] then return 0 end"));
        assertTrue(lua.contains("current['kind'] .. '\\n' .. tostring(currentId) ~= ARGV[1]"));
        assertTrue(lua.contains("newTimestamp < oldTimestamp"));
        assertTrue(lua.contains("currentStatus == 'wayline_ok'"));
        assertTrue(lua.contains("previousUpdated >= nextUpdated"));
        assertTrue(lua.contains("nextUpdated = previousUpdated + 1"));
        assertEquals(List.of(REDIS_KEY, ACTIVE_KEY), keys.getValue());
        assertTrue(stateJson.getValue().contains("\"active\":true"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedProgressGenerationIsReportedToCaller() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        assertFalse(taskStore.recordProgressIfCurrent(GATEWAY_SN, "flyto", Map.of(
                "fly_to_id", "old-generation",
                "status", "wayline_ok",
                "timestamp", 100L)));
    }

    @Test
    void progressWithoutTaskIdNeverTouchesRedis() {
        assertFalse(taskStore.recordProgressIfCurrent(GATEWAY_SN, "flyto", Map.of(
                "status", "wayline_progress",
                "timestamp", 200L)));

        verify(stringRedisTemplate, never()).execute(
                any(DefaultRedisScript.class), any(List.class),
                any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void idleAircraftFinishesOnlyAConfirmedProgressingTask() {
        String finished = "{\"sn\":\"test-gateway\",\"kind\":\"takeoff\","
                + "\"flight_id\":\"takeoff-1\",\"status\":\"task_finish\",\"active\":false}";
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), any(List.class), anyString(), anyString()))
                .thenReturn(finished);

        Map<String, Object> state = taskStore.finishProgressingTaskOnIdle(GATEWAY_SN).orElseThrow();

        ArgumentCaptor<DefaultRedisScript> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                script.capture(), eq(List.of(REDIS_KEY, ACTIVE_KEY)),
                anyString(), eq(TTL_SECONDS));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("current['status'] or '') ~= 'wayline_progress'"));
        assertTrue(lua.contains("current['status'] = 'task_finish'"));
        assertTrue(lua.contains("current['status'] = 'wayline_cancel'"));
        assertTrue(lua.contains("redis.call('del', KEYS[2])"));
        assertEquals("task_finish", state.get("status"));
        assertEquals(Boolean.FALSE, state.get("active"));
    }

    @Test
    void idleAircraftWithoutGatewayNeverTouchesRedis() {
        assertTrue(taskStore.finishProgressingTaskOnIdle("").isEmpty());

        verify(stringRedisTemplate, never()).execute(
                any(DefaultRedisScript.class), any(List.class), any(Object[].class));
    }

    @Test
    void corruptJsonFailsClosedWithoutDeletingSafetyState() {
        storedJson.set("{not-json");

        Map<String, Object> state = taskStore.get(GATEWAY_SN).orElseThrow();

        assertEquals("command_unknown", state.get("status"));
        assertEquals(Boolean.TRUE, state.get("active"));
        assertEquals(Boolean.TRUE, state.get("uncertain"));
        verify(stringRedisTemplate, never()).delete(REDIS_KEY);
    }

    @Test
    void activeGenerationWithoutMetadataAlsoFailsClosed() {
        when(valueOperations.get(ACTIVE_KEY)).thenReturn("flyto\nflyto-1");

        Map<String, Object> state = taskStore.get(GATEWAY_SN).orElseThrow();

        assertEquals("command_unknown", state.get("status"));
        assertEquals(Boolean.TRUE, state.get("active"));
        assertEquals("Point-flight metadata is unavailable.", state.get("message"));
    }
}
