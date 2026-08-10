package com.yoox.service.wayline.service.impl;

import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.service.IWaylineRedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class WaylineRedisServiceImpl implements IWaylineRedisService {

    private static final String RUNNING_OWNER_PREFIX = "wayline_job_running_owner" + RedisConst.DELIMITER;

    private static final String PAUSED_OWNER_PREFIX = "wayline_job_paused_owner" + RedisConst.DELIMITER;

    private static final String PROGRESS_WATERMARK_PREFIX = "wayline_job_progress_watermark" + RedisConst.DELIMITER;

    private static final String OPERATION_LOCK_PREFIX = "wayline_job_operation_lock" + RedisConst.DELIMITER;

    // pause + undo each perform a synchronous MQTT retry cycle. Keep enough
    // margin for both cycles, database reconciliation and transient latency.
    private static final long OPERATION_LOCK_SECONDS = 90;

    private static final DefaultRedisScript<Long> CLAIM_RUNNING = new DefaultRedisScript<>(
            "local job = ARGV[1] " +
                    "if redis.call('GET', KEYS[2]) then return 0 end " +
                    "if redis.call('GET', KEYS[4]) then return 0 end " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) " +
                    "redis.call('SET', KEYS[2], job, 'EX', ARGV[3]) " +
                    "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> APPLY_PROGRESS = new DefaultRedisScript<>(
            "local job = ARGV[1] " +
                    "local runningOwner = redis.call('GET', KEYS[2]) " +
                    "local pausedOwner = redis.call('GET', KEYS[4]) " +
                    "if runningOwner and runningOwner ~= job then return -1 end " +
                    "if pausedOwner and pausedOwner ~= job then return -1 end " +
                    "if ARGV[6] ~= '1' and pausedOwner == job then return -1 end " +
                    "local watermark = redis.call('GET', KEYS[5]) " +
                    "local eventTime = tonumber(ARGV[4]) " +
                    "if watermark and eventTime and eventTime > 0 then " +
                    "  local separator = string.find(watermark, '|', 1, true) " +
                    "  if separator then " +
                    "    local watermarkJob = string.sub(watermark, 1, separator - 1) " +
                    "    local watermarkTime = tonumber(string.sub(watermark, separator + 1)) " +
                    "    if watermarkJob == job and watermarkTime and eventTime and eventTime <= watermarkTime then return 0 end " +
                    "  end " +
                    "end " +
                    "if ARGV[6] == '1' then " +
                    "  if runningOwner == job then redis.call('DEL', KEYS[1], KEYS[2]) end " +
                    "  redis.call('SET', KEYS[3], ARGV[3], 'EX', ARGV[5]) " +
                    "  redis.call('SET', KEYS[4], job, 'EX', ARGV[5]) " +
                    "else " +
                    "  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[5]) " +
                    "  redis.call('SET', KEYS[2], job, 'EX', ARGV[5]) " +
                    "  if pausedOwner == job then redis.call('DEL', KEYS[3], KEYS[4]) end " +
                    "end " +
                    "if eventTime and eventTime > 0 then " +
                    "  redis.call('SET', KEYS[5], job .. '|' .. ARGV[4], 'EX', ARGV[5]) " +
                    "end " +
                    "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> PAUSE_RUNNING = new DefaultRedisScript<>(
            "local job = ARGV[1] " +
                    "local runningOwner = redis.call('GET', KEYS[2]) " +
                    "local pausedOwner = redis.call('GET', KEYS[4]) " +
                    "if pausedOwner == job and (not runningOwner or runningOwner == job) then " +
                    "  if runningOwner == job then redis.call('DEL', KEYS[1], KEYS[2]) end " +
                    "  redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[4]) " +
                    "  redis.call('EXPIRE', KEYS[4], ARGV[4]) " +
                    "  return 2 " +
                    "end " +
                    "if runningOwner ~= job or pausedOwner then return 0 end " +
                    "redis.call('DEL', KEYS[1], KEYS[2]) " +
                    "redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[4]) " +
                    "redis.call('SET', KEYS[4], job, 'EX', ARGV[4]) " +
                    "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> RESUME_PAUSED = new DefaultRedisScript<>(
            "local job = ARGV[1] " +
                    "local runningOwner = redis.call('GET', KEYS[2]) " +
                    "local pausedOwner = redis.call('GET', KEYS[4]) " +
                    "if pausedOwner ~= job then return 0 end " +
                    "if runningOwner and runningOwner ~= job then return 0 end " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[4]) " +
                    "redis.call('SET', KEYS[2], job, 'EX', ARGV[4]) " +
                    "redis.call('DEL', KEYS[3], KEYS[4]) " +
                    "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> REFRESH_RUNNING = new DefaultRedisScript<>(
            "local job = ARGV[1] " +
                    "if redis.call('GET', KEYS[2]) ~= job then return 0 end " +
                    "if redis.call('GET', KEYS[4]) then return 0 end " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) " +
                    "redis.call('EXPIRE', KEYS[2], ARGV[3]) " +
                    "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> CLEAR_OWNED_STATE = new DefaultRedisScript<>(
            "local job = ARGV[1] " +
                    "local changed = 0 " +
                    "if redis.call('GET', KEYS[2]) == job then " +
                    "  redis.call('DEL', KEYS[1], KEYS[2]) changed = 1 " +
                    "end " +
                    "if redis.call('GET', KEYS[4]) == job then " +
                    "  redis.call('DEL', KEYS[3], KEYS[4]) changed = 1 " +
                    "end " +
                    "local watermark = redis.call('GET', KEYS[5]) " +
                    "if watermark and string.sub(watermark, 1, string.len(job) + 1) == job .. '|' then " +
                    "  redis.call('DEL', KEYS[5]) changed = 1 " +
                    "end " +
                    "return changed",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('DEL', KEYS[1]) " +
                    "end return 0",
            Long.class);

    private static final DefaultRedisScript<Long> REPAIR_OWNER = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]) " +
                    "if not current then redis.call('DEL', KEYS[2]) return 0 end " +
                    "if current ~= ARGV[2] then return -1 end " +
                    "redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[3]) " +
                    "return 1",
            Long.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void setRunningWaylineJob(String dockSn, EventsReceiver<FlighttaskProgress> data) {
        if (!StringUtils.hasText(dockSn) || data == null || !StringUtils.hasText(data.getBid())) {
            throw new IllegalStateException("Another wayline job owns gateway runtime state.");
        }
        repairLegacyOwners(dockSn);
        Long claimed = stringRedisTemplate.execute(
                CLAIM_RUNNING,
                runtimeKeys(dockSn),
                data.getBid(),
                serialize(data),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        if (!Long.valueOf(1L).equals(claimed)) {
            throw new IllegalStateException("Another wayline job owns gateway runtime state.");
        }
    }

    @Override
    public boolean applyWaylineJobProgress(String dockSn, String jobId,
                                           EventsReceiver<FlighttaskProgress> data,
                                           long eventTimestamp, boolean paused) {
        if (!StringUtils.hasText(dockSn) || !StringUtils.hasText(jobId) || data == null) {
            return false;
        }
        repairLegacyOwners(dockSn);
        Long result = stringRedisTemplate.execute(
                APPLY_PROGRESS,
                runtimeKeys(dockSn),
                jobId,
                serialize(data),
                serialize(jobId),
                Long.toString(eventTimestamp),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString(),
                paused ? "1" : "0");
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean pauseRunningWaylineJob(String dockSn, String jobId, long transitionTimestamp) {
        repairLegacyOwners(dockSn);
        Long result = stringRedisTemplate.execute(
                PAUSE_RUNNING,
                runtimeKeys(dockSn),
                jobId,
                serialize(jobId),
                Long.toString(transitionTimestamp),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        return result != null && result > 0;
    }

    @Override
    public boolean resumePausedWaylineJob(String dockSn, String jobId,
                                          EventsReceiver<FlighttaskProgress> data,
                                          long transitionTimestamp) {
        repairLegacyOwners(dockSn);
        Long result = stringRedisTemplate.execute(
                RESUME_PAUSED,
                runtimeKeys(dockSn),
                jobId,
                serialize(data),
                Long.toString(transitionTimestamp),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean refreshRunningWaylineJob(String dockSn, String jobId,
                                            EventsReceiver<FlighttaskProgress> data) {
        repairLegacyOwners(dockSn);
        Long result = stringRedisTemplate.execute(
                REFRESH_RUNNING,
                runtimeKeys(dockSn),
                jobId,
                serialize(data),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean clearWaylineJobState(String dockSn, String jobId) {
        repairLegacyOwners(dockSn);
        Long result = stringRedisTemplate.execute(
                CLEAR_OWNED_STATE,
                runtimeKeys(dockSn),
                jobId);
        return result != null && result > 0;
    }

    @Override
    public Optional<EventsReceiver<FlighttaskProgress>> getRunningWaylineJob(String dockSn) {
        Optional<EventsReceiver<FlighttaskProgress>> running = Optional.ofNullable(
                (EventsReceiver<FlighttaskProgress>) RedisOpsUtils.get(runningKey(dockSn)));
        if (running.isPresent() && StringUtils.hasText(running.get().getBid())) {
            backfillOwner(runningOwnerKey(dockSn), runningKey(dockSn),
                    running.get().getBid(), running.get());
        } else {
            repairMissingOwner(runningOwnerKey(dockSn), runningKey(dockSn));
        }
        return running;
    }

    @Override
    @Deprecated
    public Boolean delRunningWaylineJob(String dockSn) {
        boolean deleted = RedisOpsUtils.del(runningKey(dockSn));
        if (stringRedisTemplate != null) {
            stringRedisTemplate.delete(runningOwnerKey(dockSn));
        }
        return deleted;
    }

    @Override
    public void setPausedWaylineJob(String dockSn, String jobId) {
        if (!pauseRunningWaylineJob(dockSn, jobId, System.currentTimeMillis())) {
            throw new IllegalStateException("The running wayline job changed before it could be paused.");
        }
    }

    @Override
    public String getPausedWaylineJobId(String dockSn) {
        String jobId = (String) RedisOpsUtils.get(pausedKey(dockSn));
        if (StringUtils.hasText(jobId)) {
            backfillOwner(pausedOwnerKey(dockSn), pausedKey(dockSn), jobId, jobId);
        } else {
            repairMissingOwner(pausedOwnerKey(dockSn), pausedKey(dockSn));
        }
        return jobId;
    }

    @Override
    @Deprecated
    public Boolean delPausedWaylineJob(String dockSn) {
        boolean deleted = RedisOpsUtils.del(pausedKey(dockSn));
        if (stringRedisTemplate != null) {
            stringRedisTemplate.delete(pausedOwnerKey(dockSn));
        }
        return deleted;
    }

    @Override
    public void setBlockedWaylineJob(String dockSn, String jobId) {
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_JOB_BLOCK_PREFIX + dockSn, jobId, RedisConst.WAYLINE_JOB_BLOCK_TIME);
    }

    @Override
    public String getBlockedWaylineJobId(String dockSn) {
        return (String) RedisOpsUtils.get(RedisConst.WAYLINE_JOB_BLOCK_PREFIX + dockSn);
    }

    @Override
    public void setConditionalWaylineJob(WaylineJobDTO waylineJob) {
        if (!StringUtils.hasText(waylineJob.getJobId())) {
            throw new RuntimeException("Job id can't be null.");
        }
        if (Objects.isNull(waylineJob.getEndTime())) {
            throw new IllegalArgumentException("Conditional job end time can't be null.");
        }
        long ttl = Duration.between(LocalDateTime.now(), waylineJob.getEndTime()).getSeconds();
        if (ttl <= 0) {
            throw new IllegalArgumentException("Conditional job end time must be in the future.");
        }
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + waylineJob.getJobId(), waylineJob,
                ttl);
    }

    @Override
    public Optional<WaylineJobDTO> getConditionalWaylineJob(String jobId) {
        return Optional.ofNullable((WaylineJobDTO) RedisOpsUtils.get(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + jobId));
    }

    @Override
    public Boolean delConditionalWaylineJob(String jobId) {
        return RedisOpsUtils.del(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + jobId);
    }

    @Override
    public Boolean addPrepareConditionalWaylineJob(WaylineJobDTO waylineJob) {
        if (Objects.isNull(waylineJob.getBeginTime())) {
            return false;
        }
        return RedisOpsUtils.zAdd(RedisConst.WAYLINE_JOB_CONDITION_PREPARE,
                waylineJob.getWorkspaceId() + RedisConst.DELIMITER + waylineJob.getDockSn() + RedisConst.DELIMITER + waylineJob.getJobId(),
                waylineJob.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    @Override
    public Optional<ConditionalWaylineJobKey> getNearestConditionalWaylineJob() {
        for (int attempts = 0; attempts < 100; attempts++) {
            Object member = RedisOpsUtils.zGetMin(RedisConst.WAYLINE_JOB_CONDITION_PREPARE);
            if (member == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ConditionalWaylineJobKey(member.toString()));
            } catch (IllegalArgumentException e) {
                log.warn("Removing malformed conditional wayline queue member: {}", member);
                RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, member);
            }
        }
        throw new IllegalStateException("Too many malformed conditional wayline queue members.");
    }

    @Override
    public Double getConditionalWaylineJobTime(ConditionalWaylineJobKey jobKey) {
        return RedisOpsUtils.zScore(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, jobKey.getKey());
    }

    @Override
    public Boolean removePrepareConditionalWaylineJob(ConditionalWaylineJobKey jobKey) {
        return RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, jobKey.getKey());
    }

    @Override
    public Optional<String> tryAcquireWaylineJobOperation(String dockSn) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                operationLockKey(dockSn), token, OPERATION_LOCK_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public boolean releaseWaylineJobOperation(String dockSn, String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        Long released = stringRedisTemplate.execute(
                RELEASE_LOCK, List.of(operationLockKey(dockSn)), token);
        return Long.valueOf(1L).equals(released);
    }

    private List<String> runtimeKeys(String dockSn) {
        return List.of(
                runningKey(dockSn), runningOwnerKey(dockSn),
                pausedKey(dockSn), pausedOwnerKey(dockSn),
                progressWatermarkKey(dockSn));
    }

    private String runningKey(String dockSn) {
        return RedisConst.WAYLINE_JOB_RUNNING_PREFIX + dockSn;
    }

    private String runningOwnerKey(String dockSn) {
        return RUNNING_OWNER_PREFIX + dockSn;
    }

    private String pausedKey(String dockSn) {
        return RedisConst.WAYLINE_JOB_PAUSED_PREFIX + dockSn;
    }

    private String pausedOwnerKey(String dockSn) {
        return PAUSED_OWNER_PREFIX + dockSn;
    }

    private String progressWatermarkKey(String dockSn) {
        return PROGRESS_WATERMARK_PREFIX + dockSn;
    }

    private String operationLockKey(String dockSn) {
        return OPERATION_LOCK_PREFIX + dockSn;
    }

    private void repairLegacyOwners(String dockSn) {
        getRunningWaylineJob(dockSn);
        getPausedWaylineJobId(dockSn);
    }

    private void backfillOwner(String ownerKey, String dataKey, String jobId, Object expectedData) {
        if (stringRedisTemplate == null) {
            return;
        }
        long ttl = RedisOpsUtils.getExpire(dataKey);
        if (ttl <= 0) {
            ttl = RedisConst.DRC_MODE_ALIVE_SECOND;
        }
        stringRedisTemplate.execute(
                REPAIR_OWNER,
                List.of(dataKey, ownerKey),
                jobId,
                serialize(expectedData),
                Long.toString(ttl));
    }

    private void repairMissingOwner(String ownerKey, String dataKey) {
        if (stringRedisTemplate != null) {
            stringRedisTemplate.execute(
                    REPAIR_OWNER,
                    List.of(dataKey, ownerKey),
                    "",
                    "",
                    RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private String serialize(Object value) {
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
        byte[] bytes = serializer.serialize(value);
        if (bytes == null) {
            throw new IllegalArgumentException("Wayline runtime state cannot be serialized.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
