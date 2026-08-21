package com.yoox.service.control.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Persists and serializes takeoff/FlyTo commands per gateway.
 *
 * <p>The task id is also the generation token. Both HTTP command admission and
 * device progress use Redis-side compare-and-set scripts, so concurrent app
 * instances and delayed events cannot start or overwrite another generation.</p>
 */
@Component
public class PointFlightTaskStore {

    private static final String KEY_PREFIX = "control:point-flight:";
    private static final String ACTIVE_PREFIX = "control:point-flight:active:";
    private static final String LAST_TAKEOFF_PREFIX = "control:point-flight:last-takeoff:";
    private static final long TTL_HOURS = 24;
    private static final long TTL_SECONDS = TimeUnit.HOURS.toSeconds(TTL_HOURS);
    private static final long STALE_TAKEOFF_IDLE_GRACE_MS = TimeUnit.MINUTES.toMillis(2);
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "task_finish", "wayline_cancel", "wayline_failed", "wayline_ok",
            "command_failed", "cancel_confirmed");

    private static final DefaultRedisScript<Long> CLAIM_TASK = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[2]) == 1 then return 0 end; "
                    + "local nextOk, next = pcall(cjson.decode, ARGV[1]); "
                    + "if not nextOk then return -1 end; "
                    + "local nextUpdated = tonumber(ARGV[3]); if not nextUpdated then return -1 end; "
                    + "local raw = redis.call('get', KEYS[1]); "
                    + "if raw then local ok, current = pcall(cjson.decode, raw); "
                    + "if not ok then return -1 end; "
                    + "if current['active'] == true then return 0 end; "
                    + "local previousUpdated = tonumber(current['updated_at']); "
                    + "if previousUpdated and previousUpdated >= nextUpdated then "
                    + "nextUpdated = previousUpdated + 1; end; end; "
                    + "next['created_at'] = nextUpdated; next['updated_at'] = nextUpdated; "
                    + "redis.call('set', KEYS[1], cjson.encode(next), 'EX', ARGV[4]); "
                    + "redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[4]); return 1",
            Long.class);

    private static final DefaultRedisScript<Long> UPDATE_COMMAND = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[2]) ~= ARGV[1] then return 0 end; "
                    + "local raw = redis.call('get', KEYS[1]); if not raw then return -1 end; "
                    + "local ok, current = pcall(cjson.decode, raw); if not ok then return -1 end; "
                    + "local currentId = current['fly_to_id'] or current['flight_id']; "
                    + "if not currentId or not current['kind'] "
                    + "or current['kind'] .. '\\n' .. tostring(currentId) ~= ARGV[1] then return 0 end; "
                    + "local status = tostring(current['status'] or ''); "
                    + "if status ~= '' and string.sub(status, 1, 8) ~= 'command_' then return 0 end; "
                    + "current['status'] = ARGV[2]; current['active'] = ARGV[3] == '1'; "
                    + "current['uncertain'] = ARGV[4] == '1'; "
                    + "if ARGV[5] == '' then current['message'] = nil else current['message'] = ARGV[5] end; "
                    + "local nextUpdated = tonumber(ARGV[6]); if not nextUpdated then return -1 end; "
                    + "local previousUpdated = tonumber(current['updated_at']); "
                    + "if previousUpdated and previousUpdated >= nextUpdated then "
                    + "nextUpdated = previousUpdated + 1; end; current['updated_at'] = nextUpdated; "
                    + "redis.call('set', KEYS[1], cjson.encode(current), 'EX', ARGV[7]); "
                    + "if ARGV[3] == '1' then redis.call('expire', KEYS[2], ARGV[7]); "
                    + "elseif redis.call('get', KEYS[2]) == ARGV[1] then redis.call('del', KEYS[2]); end; return 1",
            Long.class);

    private static final DefaultRedisScript<Long> UPDATE_CANCEL = new DefaultRedisScript<>(
            "local token = redis.call('get', KEYS[2]); if not token then return 0 end; "
                    + "local raw = redis.call('get', KEYS[1]); if not raw then return -1 end; "
                    + "local ok, current = pcall(cjson.decode, raw); if not ok then return -1 end; "
                    + "local currentId = current['fly_to_id'] or current['flight_id']; "
                    + "if not currentId or not current['kind'] "
                    + "or current['kind'] .. '\\n' .. tostring(currentId) ~= token then return 0 end; "
                    + "if current['active'] ~= true then return 0 end; "
                    + "current['status'] = ARGV[1]; current['uncertain'] = ARGV[2] == '1'; "
                    + "if ARGV[3] ~= '' then current['message'] = ARGV[3] end; "
                    + "local nextUpdated = tonumber(ARGV[4]); if not nextUpdated then return -1 end; "
                    + "local previousUpdated = tonumber(current['updated_at']); "
                    + "if previousUpdated and previousUpdated >= nextUpdated then "
                    + "nextUpdated = previousUpdated + 1; end; current['updated_at'] = nextUpdated; "
                    + "redis.call('set', KEYS[1], cjson.encode(current), 'EX', ARGV[5]); "
                    + "redis.call('expire', KEYS[2], ARGV[5]); return 1",
            Long.class);

    private static final DefaultRedisScript<Long> UPDATE_PROGRESS = new DefaultRedisScript<>(
            "local claim = redis.call('get', KEYS[2]); "
                    + "if claim ~= ARGV[1] then return 0 end; "
                    + "local raw = redis.call('get', KEYS[1]); if not raw then return -1 end; "
                    + "local ok, current = pcall(cjson.decode, raw); if not ok then return -1 end; "
                    + "local currentId = current['fly_to_id'] or current['flight_id']; "
                    + "if not currentId or not current['kind'] "
                    + "or current['kind'] .. '\\n' .. tostring(currentId) ~= ARGV[1] then return 0 end; "
                    + "local currentStatus = tostring(current['status'] or ''); "
                    + "if currentStatus == 'task_finish' "
                    + "or currentStatus == 'wayline_cancel' or currentStatus == 'wayline_failed' "
                    + "or currentStatus == 'wayline_ok' or currentStatus == 'command_failed' "
                    + "or currentStatus == 'cancel_confirmed' then return 0 end; "
                    + "local oldTimestamp = tonumber(current['timestamp']); local newTimestamp = tonumber(ARGV[3]); "
                    + "if oldTimestamp and newTimestamp and newTimestamp < oldTimestamp then return 0 end; "
                    + "local next = cjson.decode(ARGV[2]); "
                    + "local nextUpdated = tonumber(next['updated_at']); if not nextUpdated then return -1 end; "
                    + "local previousUpdated = tonumber(current['updated_at']); "
                    + "if previousUpdated and previousUpdated >= nextUpdated then "
                    + "nextUpdated = previousUpdated + 1; end; next['updated_at'] = nextUpdated; "
                    + "if current['created_at'] then "
                    + "next['created_at'] = current['created_at']; end; "
                    + "redis.call('set', KEYS[1], cjson.encode(next), 'EX', ARGV[5]); "
                    + "if ARGV[4] == '1' then redis.call('set', KEYS[2], ARGV[1], 'EX', ARGV[5]); "
                    + "elseif redis.call('get', KEYS[2]) == ARGV[1] then redis.call('del', KEYS[2]); end; return 1",
            Long.class);

    private static final DefaultRedisScript<Long> CONFIRM_CANCEL = new DefaultRedisScript<>(
            "local token = redis.call('get', KEYS[2]); if not token then return 0 end; "
                    + "local raw = redis.call('get', KEYS[1]); if not raw then return -1 end; "
                    + "local ok, current = pcall(cjson.decode, raw); if not ok then return -1 end; "
                    + "local currentId = current['fly_to_id'] or current['flight_id']; "
                    + "if not currentId or not current['kind'] "
                    + "or current['kind'] .. '\\n' .. tostring(currentId) ~= token then return 0 end; "
                    + "if current['active'] ~= true then return 0 end; "
                    + "current['status'] = 'cancel_confirmed'; "
                    + "current['active'] = false; current['uncertain'] = false; "
                    + "if ARGV[1] ~= '' then current['message'] = ARGV[1] end; "
                    + "local nextUpdated = tonumber(ARGV[2]); if not nextUpdated then return -1 end; "
                    + "local previousUpdated = tonumber(current['updated_at']); "
                    + "if previousUpdated and previousUpdated >= nextUpdated then "
                    + "nextUpdated = previousUpdated + 1; end; current['updated_at'] = nextUpdated; "
                    + "redis.call('set', KEYS[1], cjson.encode(current), 'EX', ARGV[3]); "
                    + "if redis.call('get', KEYS[2]) == token then redis.call('del', KEYS[2]); end; return 1",
            Long.class);

    private static final DefaultRedisScript<String> FINISH_PROGRESSING_TASK_ON_IDLE =
            new DefaultRedisScript<>(
                    "local token = redis.call('get', KEYS[2]); if not token then return nil end; "
                            + "local raw = redis.call('get', KEYS[1]); if not raw then return nil end; "
                            + "local ok, current = pcall(cjson.decode, raw); if not ok then return nil end; "
                            + "local currentId = current['fly_to_id'] or current['flight_id']; "
                            + "if not currentId or not current['kind'] "
                            + "or current['kind'] .. '\\n' .. tostring(currentId) ~= token then return nil end; "
                            + "if current['active'] ~= true then return nil end; "
                            + "local status = tostring(current['status'] or ''); "
                            + "local updated = tonumber(current['updated_at']); "
                            + "local staleBefore = tonumber(ARGV[5]); "
                            + "local staleTakeoff = ARGV[3] == '0' and current['kind'] == 'takeoff' "
                            + "and updated and staleBefore and updated <= staleBefore "
                            + "and (status == 'command_pending' or status == 'command_accepted' "
                            + "or status == 'command_unknown'); "
                            + "if status ~= 'wayline_progress' and not staleTakeoff then return nil end; "
                            + "if ARGV[3] == '1' and current['kind'] ~= 'takeoff' then return nil end; "
                            + "if staleTakeoff then current['status'] = 'command_failed'; "
                            + "elseif current['kind'] == 'takeoff' then current['status'] = 'task_finish'; "
                            + "else current['status'] = 'wayline_cancel'; end; "
                            + "current['active'] = false; current['uncertain'] = false; "
                            + "current['remaining_distance'] = 0; current['remaining_time'] = 0; "
                            + "if staleTakeoff then current['message'] = ARGV[6]; "
                            + "else current['message'] = ARGV[4]; end; "
                            + "local nextUpdated = tonumber(ARGV[1]); if not nextUpdated then return nil end; "
                            + "local previousUpdated = tonumber(current['updated_at']); "
                            + "if previousUpdated and previousUpdated >= nextUpdated then "
                            + "nextUpdated = previousUpdated + 1; end; current['updated_at'] = nextUpdated; "
                            + "local next = cjson.encode(current); "
                            + "redis.call('set', KEYS[1], next, 'EX', ARGV[2]); "
                            + "if redis.call('get', KEYS[2]) == token then redis.call('del', KEYS[2]); end; "
                            + "return next",
                    String.class);

    private static final DefaultRedisScript<Long> CLEAR_LAST_TAKEOFF = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end; return 0",
            Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Atomically claims the gateway for a new task generation.
     */
    public boolean tryRecordPending(String gatewaySn, String kind, String taskId) {
        if (!validTask(gatewaySn, kind, taskId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> state = baseState(gatewaySn, kind, taskId, now);
        state.put("status", "command_pending");
        state.put("active", true);
        state.put("uncertain", false);
        Long result = stringRedisTemplate.execute(
                CLAIM_TASK,
                List.of(key(gatewaySn), activeKey(gatewaySn)),
                json(state), taskToken(kind, taskId), Long.toString(now),
                Long.toString(TTL_SECONDS));
        return Long.valueOf(1).equals(result);
    }

    /**
     * Backwards-compatible entry point for callers that already proved the
     * gateway is idle. New command paths should use {@link #tryRecordPending}.
     */
    public void recordPending(String gatewaySn, String kind, String taskId) {
        if (!tryRecordPending(gatewaySn, kind, taskId)) {
            throw new IllegalStateException("A point-flight generation is already active.");
        }
    }

    public void recordAccepted(String gatewaySn, String kind, String taskId) {
        updateCommand(gatewaySn, kind, taskId,
                "command_accepted", true, false, null);
        // RC 不上报 takeoff 的终结事件且内部任务会一直挂着；单独记住最近被接受的
        // takeoff flight_id（不随后续 flyto 覆盖），供航线执行遇 104 时定向 undo。
        if ("takeoff".equals(kind) && validTask(gatewaySn, kind, taskId)) {
            stringRedisTemplate.opsForValue().set(
                    lastTakeoffKey(gatewaySn), taskId, TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 最近一次被设备接受、尚未确认被 undo 清除的 takeoff flight_id。 */
    public Optional<String> getLastAcceptedTakeoffId(String gatewaySn) {
        if (!StringUtils.hasText(gatewaySn)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                stringRedisTemplate.opsForValue().get(lastTakeoffKey(gatewaySn)))
                .filter(StringUtils::hasText);
    }

    /** 仅当记录仍是该 flight_id 时才删除，避免误删并发新起飞的记录。 */
    public void clearLastAcceptedTakeoffId(String gatewaySn, String flightId) {
        if (!StringUtils.hasText(gatewaySn) || !StringUtils.hasText(flightId)) {
            return;
        }
        stringRedisTemplate.execute(
                CLEAR_LAST_TAKEOFF, List.of(lastTakeoffKey(gatewaySn)), flightId);
    }

    public void recordUnknown(String gatewaySn, String kind, String taskId, String message) {
        updateCommand(gatewaySn, kind, taskId,
                "command_unknown", true, true, message);
    }

    public void recordFailure(String gatewaySn, String kind, String taskId, String message) {
        updateCommand(gatewaySn, kind, taskId,
                "command_failed", false, false, message);
    }

    public void recordCancelRequested(String gatewaySn, boolean uncertain, String message) {
        updateCancel(gatewaySn, uncertain ? "cancel_unknown" : "cancel_requested",
                uncertain, message);
    }

    public void recordCancelFailure(String gatewaySn, String message) {
        updateCancel(gatewaySn, "cancel_failed", false, message);
    }

    /**
     * 设备已确认停止指令（result=0）：任务进入终态并释放占用，
     * 否则从未在设备上真正启动过的任务会一直阻塞新指令直到 TTL 过期。
     */
    public void recordCancelConfirmed(String gatewaySn, String message) {
        if (!StringUtils.hasText(gatewaySn)) {
            return;
        }
        stringRedisTemplate.execute(
                CONFIRM_CANCEL,
                List.of(key(gatewaySn), activeKey(gatewaySn)),
                StringUtils.hasText(message) ? message : "",
                Long.toString(System.currentTimeMillis()), Long.toString(TTL_SECONDS));
    }

    public void recordProgress(String gatewaySn, String kind, Map<String, Object> progress) {
        recordProgressIfCurrent(gatewaySn, kind, progress);
    }

    /**
     * Applies device progress only to the matching active generation. Terminal
     * states are irreversible, and an old task can never overwrite a newer
     * active claim.
     */
    public boolean recordProgressIfCurrent(
            String gatewaySn, String kind, Map<String, Object> progress) {
        String taskId = progressTaskId(kind, progress);
        String status = progress == null
                ? null
                : String.valueOf(progress.getOrDefault("status", ""));
        if (!validTask(gatewaySn, kind, taskId) || !StringUtils.hasText(status)) {
            return false;
        }
        Map<String, Object> state = new LinkedHashMap<>(progress);
        state.put("sn", gatewaySn);
        state.put("kind", kind);
        boolean active = !TERMINAL_STATUSES.contains(status);
        state.put("active", active);
        state.put("uncertain", false);
        state.put("updated_at", System.currentTimeMillis());
        Object timestamp = state.get("timestamp");
        String timestampArg = timestamp instanceof Number
                ? Long.toString(((Number) timestamp).longValue())
                : "";
        Long result = stringRedisTemplate.execute(
                UPDATE_PROGRESS,
                List.of(key(gatewaySn), activeKey(gatewaySn)),
                taskToken(kind, taskId), json(state), timestampArg,
                active ? "1" : "0", Long.toString(TTL_SECONDS));
        return Long.valueOf(1).equals(result);
    }

    public Optional<Map<String, Object>> get(String gatewaySn) {
        if (!StringUtils.hasText(gatewaySn)) {
            return Optional.empty();
        }
        String json = stringRedisTemplate.opsForValue().get(key(gatewaySn));
        if (StringUtils.hasText(json)) {
            try {
                return Optional.of(objectMapper.readValue(
                        json, new TypeReference<Map<String, Object>>() { }));
            } catch (JsonProcessingException exception) {
                // Never fail open on corrupt command state. The active token is
                // deliberately retained so a potentially executing task cannot
                // be followed by a duplicate command.
                return Optional.of(unknownActiveState(gatewaySn, "Point-flight state is corrupt."));
            }
        }
        String token = stringRedisTemplate.opsForValue().get(activeKey(gatewaySn));
        return StringUtils.hasText(token)
                ? Optional.of(unknownActiveState(gatewaySn, "Point-flight metadata is unavailable."))
                : Optional.empty();
    }

    public boolean hasPotentiallyActiveTask(String gatewaySn) {
        if (!StringUtils.hasText(gatewaySn)) {
            return false;
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(activeKey(gatewaySn)))) {
            return true;
        }
        return get(gatewaySn)
                .map(state -> Boolean.TRUE.equals(state.get("active")))
                .orElse(false);
    }

    /**
     * Finishes a task that was confirmed in progress once aircraft OSD reports
     * idle. This covers manual/RC landing when the device omits the terminal
     * point-flight event, without clearing a command that has not taken off yet.
     */
    public Optional<Map<String, Object>> finishProgressingTaskOnIdle(String gatewaySn) {
        return finishProgressingTask(
                gatewaySn, false, "Aircraft returned to idle mode.",
                System.currentTimeMillis() - STALE_TAKEOFF_IDLE_GRACE_MS,
                "Released stale takeoff command because the aircraft remained idle.");
    }

    /**
     * RC 不会上报起飞任务的终结事件：到点后飞机转为 MANUAL 悬停，任务会
     * 一直占用点飞额度导致指点飞行被拦截。MANUAL 即视为起飞完成，
     * 仅收尾 takeoff 任务（flyto 飞行中不是 MANUAL 模式，不受影响）。
     */
    public Optional<Map<String, Object>> finishProgressingTakeoffOnManual(String gatewaySn) {
        return finishProgressingTask(
                gatewaySn, true, "Takeoff completed. The aircraft is hovering.",
                0, "");
    }

    private Optional<Map<String, Object>> finishProgressingTask(
            String gatewaySn, boolean onlyTakeoff, String message,
            long staleTakeoffBefore, String staleTakeoffMessage) {
        if (!StringUtils.hasText(gatewaySn)) {
            return Optional.empty();
        }
        String stateJson = stringRedisTemplate.execute(
                FINISH_PROGRESSING_TASK_ON_IDLE,
                List.of(key(gatewaySn), activeKey(gatewaySn)),
                Long.toString(System.currentTimeMillis()), Long.toString(TTL_SECONDS),
                onlyTakeoff ? "1" : "0", message,
                Long.toString(staleTakeoffBefore), staleTakeoffMessage);
        if (!StringUtils.hasText(stateJson)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    stateJson, new TypeReference<Map<String, Object>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read reconciled point-flight state.", exception);
        }
    }

    private void updateCommand(
            String gatewaySn, String kind, String taskId, String status,
            boolean active, boolean uncertain, String message) {
        if (!validTask(gatewaySn, kind, taskId)) {
            return;
        }
        stringRedisTemplate.execute(
                UPDATE_COMMAND,
                List.of(key(gatewaySn), activeKey(gatewaySn)),
                taskToken(kind, taskId), status, active ? "1" : "0",
                uncertain ? "1" : "0", StringUtils.hasText(message) ? message : "",
                Long.toString(System.currentTimeMillis()), Long.toString(TTL_SECONDS));
    }

    private void updateCancel(
            String gatewaySn, String status, boolean uncertain, String message) {
        if (!StringUtils.hasText(gatewaySn)) {
            return;
        }
        stringRedisTemplate.execute(
                UPDATE_CANCEL,
                List.of(key(gatewaySn), activeKey(gatewaySn)),
                status, uncertain ? "1" : "0",
                StringUtils.hasText(message) ? message : "",
                Long.toString(System.currentTimeMillis()), Long.toString(TTL_SECONDS));
    }

    private Map<String, Object> baseState(
            String gatewaySn, String kind, String taskId, long now) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sn", gatewaySn);
        state.put("kind", kind);
        if ("flyto".equals(kind)) {
            state.put("fly_to_id", taskId);
        } else {
            state.put("flight_id", taskId);
        }
        state.put("created_at", now);
        state.put("updated_at", now);
        return state;
    }

    private Map<String, Object> unknownActiveState(String gatewaySn, String message) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sn", gatewaySn);
        state.put("status", "command_unknown");
        state.put("active", true);
        state.put("uncertain", true);
        state.put("message", message);
        return state;
    }

    private String progressTaskId(String kind, Map<String, Object> progress) {
        if (progress == null) {
            return null;
        }
        Object value = "flyto".equals(kind)
                ? progress.get("fly_to_id")
                : progress.get("flight_id");
        return value == null ? null : String.valueOf(value);
    }

    private boolean validTask(String gatewaySn, String kind, String taskId) {
        return StringUtils.hasText(gatewaySn)
                && ("flyto".equals(kind) || "takeoff".equals(kind))
                && StringUtils.hasText(taskId);
    }

    private String taskToken(String kind, String taskId) {
        return kind + "\n" + taskId;
    }

    private String json(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist point-flight state.", exception);
        }
    }

    private String key(String gatewaySn) {
        return KEY_PREFIX + gatewaySn;
    }

    private String activeKey(String gatewaySn) {
        return ACTIVE_PREFIX + gatewaySn;
    }

    private String lastTakeoffKey(String gatewaySn) {
        return LAST_TAKEOFF_PREFIX + gatewaySn;
    }
}
