package com.yoox.service.wayline.service.impl;

import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaylineRedisServiceImplTest {

    private static final String JOB_ID = "job-1";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> stringValueOperations;

    @Mock
    private RedisSerializer<Object> valueSerializer;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    private WaylineRedisServiceImpl waylineRedisService;

    @BeforeEach
    void setUp() {
        new RedisOpsUtils().setRedisTemplate(redisTemplate);
        waylineRedisService = new WaylineRedisServiceImpl();
        ReflectionTestUtils.setField(waylineRedisService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(waylineRedisService, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void futureConditionalJobUsesPositiveRemainingTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        WaylineJobDTO job = WaylineJobDTO.builder()
                .jobId(JOB_ID)
                .endTime(LocalDateTime.now().plusSeconds(120))
                .build();

        waylineRedisService.setConditionalWaylineJob(job);

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(
                eq(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + JOB_ID),
                same(job),
                ttlCaptor.capture(),
                eq(TimeUnit.SECONDS));
        assertTrue(ttlCaptor.getValue() > 0);
        assertTrue(ttlCaptor.getValue() <= 120);
    }

    @Test
    void expiredConditionalJobIsRejectedWithoutWritingRedis() {
        WaylineJobDTO job = WaylineJobDTO.builder()
                .jobId(JOB_ID)
                .endTime(LocalDateTime.now().minusSeconds(1))
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                waylineRedisService.setConditionalWaylineJob(job));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void progressUsesSingleAtomicOwnerAndTimestampScript() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);
        doReturn(valueSerializer).when(redisTemplate).getValueSerializer();
        when(valueSerializer.serialize(any())).thenReturn("serialized".getBytes(StandardCharsets.UTF_8));
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        EventsReceiver<FlighttaskProgress> progress = EventsReceiver.<FlighttaskProgress>builder()
                .bid(JOB_ID).sn("gateway").build();

        assertTrue(waylineRedisService.applyWaylineJobProgress(
                "gateway", JOB_ID, progress, 1_000L, false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                scriptCaptor.capture(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("runningOwner ~= job"));
        assertTrue(script.contains("eventTime <= watermarkTime"));
        assertTrue(script.contains("pausedOwner == job"));
    }

    @Test
    void executionClaimAtomicallyChecksBothOwnersBeforeWriting() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);
        doReturn(valueSerializer).when(redisTemplate).getValueSerializer();
        when(valueSerializer.serialize(any())).thenReturn("serialized".getBytes(StandardCharsets.UTF_8));
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L, 0L, 1L);

        waylineRedisService.setRunningWaylineJob("gateway", EventsReceiver.<FlighttaskProgress>builder()
                .bid(JOB_ID).sn("gateway").build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate, org.mockito.Mockito.times(3)).execute(
                scriptCaptor.capture(), anyList(), anyString(), anyString(), anyString());
        String script = scriptCaptor.getAllValues().get(2).getScriptAsString();
        assertTrue(script.contains("redis.call('GET', KEYS[2])"));
        assertTrue(script.contains("redis.call('GET', KEYS[4])"));
        assertTrue(script.contains("redis.call('SET', KEYS[2], job"));
    }

    @Test
    void staleProgressIsRejectedWithoutWritingThroughJavaFallback() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);
        doReturn(valueSerializer).when(redisTemplate).getValueSerializer();
        when(valueSerializer.serialize(any())).thenReturn("serialized".getBytes(StandardCharsets.UTF_8));
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        assertFalse(waylineRedisService.applyWaylineJobProgress(
                "gateway", JOB_ID,
                EventsReceiver.<FlighttaskProgress>builder().bid(JOB_ID).build(),
                999L, false));
    }

    @Test
    void malformedConditionalQueueMemberIsRemovedAndNextValidMemberReturned() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, 0, 0))
                .thenReturn(Set.of("malformed"), Set.of("workspace:gateway:job"));
        when(zSetOperations.remove(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, "malformed"))
                .thenReturn(1L);

        Optional<ConditionalWaylineJobKey> result =
                waylineRedisService.getNearestConditionalWaylineJob();

        assertTrue(result.isPresent());
        assertEquals("workspace:gateway:job", result.get().getKey());
        verify(zSetOperations).remove(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, "malformed");
    }

    @Test
    void clearStateUsesAtomicOwnerCompareInsteadOfReadThenDelete() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);

        assertTrue(waylineRedisService.clearWaylineJobState("gateway", JOB_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                scriptCaptor.capture(), anyList(), anyString());
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("redis.call('GET', KEYS[2]) == job"));
        assertTrue(script.contains("redis.call('DEL', KEYS[1], KEYS[2])"));
        assertTrue(script.contains("redis.call('GET', KEYS[4]) == job"));
    }
}
