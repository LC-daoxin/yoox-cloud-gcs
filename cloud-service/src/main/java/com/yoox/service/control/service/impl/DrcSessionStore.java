package com.yoox.service.control.service.impl;

import com.yoox.great.redis.RedisConst;
import com.yoox.service.control.model.dto.DrcSession;
import com.yoox.service.control.model.enums.MqttAclAccessEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owns DRC leases and their MQTT ACL lifecycle.
 *
 * <p>A gateway has one atomically-acquired generation. Session metadata,
 * browser ownership and device/browser MQTT clients are server-owned so a
 * caller cannot refresh or terminate another user's control channel.</p>
 */
@Component
public class DrcSessionStore {

    public enum SessionState {
        ENTERING,
        ACTIVE,
        UNCERTAIN,
        EXITING,
        EVENT_CLEANING
    }

    public enum ExitPreparation {
        STARTED_ACTIVE,
        STARTED_UNCERTAIN,
        STARTED_ENTERING,
        RETRY_EXITING,
        RECOVERED_UNKNOWN,
        REJECTED
    }

    private static final String SESSION_PREFIX = RedisConst.DRC_PREFIX + "session:";
    private static final String STATE_PREFIX = RedisConst.DRC_PREFIX + "state:";
    private static final String OWNER_PREFIX = RedisConst.DRC_PREFIX + "owner:";
    private static final String CLIENT_SESSION_PREFIX = RedisConst.DRC_PREFIX + "client-session:";
    private static final String RECOVERY_OWNER_PREFIX = RedisConst.DRC_PREFIX + "lease-owner:";
    private static final String RECOVERY_BROWSER_PREFIX = RedisConst.DRC_PREFIX + "lease-browser:";
    private static final String PAUSED_JOB_PREFIX = RedisConst.DRC_PREFIX + "paused-job:";
    private static final String WATERMARK_PREFIX = RedisConst.DRC_PREFIX + "device-watermark:";

    private static final String FIELD_GATEWAY = "gateway_sn";
    private static final String FIELD_WORKSPACE = "workspace_id";
    private static final String FIELD_USER = "user_id";
    private static final String FIELD_BROWSER_CLIENT = "browser_client_id";
    private static final String FIELD_DEVICE_CLIENT = "device_client_id";
    private static final String FIELD_CONTROL_TOPIC_SN = "control_topic_sn";
    private static final String FIELD_GENERATION = "generation";
    private static final String FIELD_PAUSED_JOB = "paused_job_id";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_DEVICE_WATERMARK = "device_timestamp_watermark";

    private static final DefaultRedisScript<Long> COMPARE_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> ACQUIRE_SESSION = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end; "
                    + "if redis.call('exists', KEYS[2]) == 1 then return -1 end; "
                    + "redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[4]); "
                    + "redis.call('set', KEYS[2], ARGV[1], 'EX', ARGV[4]); "
                    + "redis.call('set', KEYS[3], ARGV[2], 'EX', ARGV[4]); "
                    + "redis.call('set', KEYS[4], ARGV[3], 'EX', ARGV[4]); return 1",
            Long.class);

    private static final DefaultRedisScript<Long> TRANSITION_STATE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "if redis.call('get', KEYS[2]) ~= ARGV[2] then return 0 end; "
                    + "redis.call('set', KEYS[2], ARGV[3], 'EX', ARGV[4]); "
                    + "for i = 1, #KEYS do "
                    + "if i ~= 2 and redis.call('exists', KEYS[i]) == 1 then "
                    + "redis.call('expire', KEYS[i], ARGV[4]); end; end; return 1",
            Long.class);

    private static final DefaultRedisScript<Long> PREPARE_EXIT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
            + "local state = redis.call('get', KEYS[2]); local result = 5; "
                    + "if state == 'EVENT_CLEANING' then return 0; "
                    + "elseif state == 'ACTIVE' then result = 1; "
                    + "elseif state == 'UNCERTAIN' then result = 2; "
                    + "elseif state == 'ENTERING' then result = 3; "
                    + "elseif state == 'EXITING' then result = 4; end; "
                    + "redis.call('set', KEYS[2], 'EXITING', 'EX', ARGV[2]); "
                    + "for i = 1, #KEYS do "
                    + "if i ~= 2 and redis.call('exists', KEYS[i]) == 1 then "
                    + "redis.call('expire', KEYS[i], ARGV[2]); end; end; return result",
            Long.class);

    private static final DefaultRedisScript<Long> CLAIM_EVENT_CLEANUP = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "local state = redis.call('get', KEYS[2]); "
                    + "if state == 'EXITING' then return 0 end; "
                    + "local result = 1; if state == 'EVENT_CLEANING' then result = 2 end; "
                    + "redis.call('set', KEYS[2], 'EVENT_CLEANING', 'EX', ARGV[2]); "
                    + "for i = 1, #KEYS do "
                    + "if i ~= 2 and redis.call('exists', KEYS[i]) == 1 then "
                    + "redis.call('expire', KEYS[i], ARGV[2]); end; end; return result",
            Long.class);

    private static final DefaultRedisScript<Long> REFRESH_SESSION = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "if redis.call('exists', KEYS[2]) == 0 then return 0 end; "
                    + "for i = 1, #KEYS do if redis.call('exists', KEYS[i]) == 1 then "
                    + "redis.call('expire', KEYS[i], ARGV[2]); end; end; return 1",
            Long.class);

    private static final DefaultRedisScript<Long> REBIND_BROWSER_CLIENT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "if redis.call('get', KEYS[2]) ~= 'ACTIVE' then return -1 end; "
                    + "if redis.call('hget', KEYS[3], ARGV[8]) ~= ARGV[2] then return 0 end; "
                    + "if redis.call('get', KEYS[4]) ~= ARGV[1] then return 0 end; "
                    + "if redis.call('exists', KEYS[5]) == 1 then return -2 end; "
                    + "if redis.call('get', KEYS[6]) ~= ARGV[4] then return 0 end; "
                    + "if redis.call('get', KEYS[7]) ~= ARGV[6] then return -3 end; "
                    + "redis.call('hset', KEYS[3], ARGV[8], ARGV[3]); "
                    + "redis.call('del', KEYS[4]); redis.call('del', KEYS[9]); "
                    + "redis.call('set', KEYS[5], ARGV[1], 'EX', ARGV[7]); "
                    + "redis.call('set', KEYS[6], ARGV[5], 'EX', ARGV[7]); "
                    + "for i = 1, 3 do redis.call('expire', KEYS[i], ARGV[7]); end; "
                    + "redis.call('expire', KEYS[7], ARGV[7]); "
                    + "if redis.call('exists', KEYS[8]) == 1 then "
                    + "redis.call('expire', KEYS[8], ARGV[7]); end; return 1",
            Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public String createOwnedBrowserClient(String workspaceId, String userId) {
        String owner = ownerToken(workspaceId, userId);
        for (int attempt = 0; attempt < 3; attempt++) {
            String clientId = userId + "-" + UUID.randomUUID();
            Boolean created = stringRedisTemplate.opsForValue().setIfAbsent(
                    ownerKey(clientId), owner,
                    RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(created)) {
                continue;
            }
            try {
                expireOwnedBrowserClient(workspaceId, userId, clientId);
                return clientId;
            } catch (RuntimeException exception) {
                stringRedisTemplate.delete(ownerKey(clientId));
                deleteClientAcl(clientId);
                throw exception;
            }
        }
        throw new IllegalStateException("Unable to allocate a DRC browser client.");
    }

    public void assertBrowserClientOwner(String workspaceId, String userId, String clientId) {
        if (!StringUtils.hasText(clientId)
                || !ownerToken(workspaceId, userId).equals(
                stringRedisTemplate.opsForValue().get(ownerKey(clientId)))) {
            throw new SecurityException("The DRC client does not belong to this user and workspace.");
        }
    }

    public void expireOwnedBrowserClient(String workspaceId, String userId, String clientId) {
        assertBrowserClientOwner(workspaceId, userId, clientId);
        stringRedisTemplate.expire(
                ownerKey(clientId), RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);
        if (hasClientAcl(clientId)) {
            expireClientAcl(clientId);
        }
    }

    public void deleteOwnedBrowserClient(String workspaceId, String userId, String clientId) {
        assertBrowserClientOwner(workspaceId, userId, clientId);
        deleteClientAcl(clientId);
        stringRedisTemplate.expire(
                ownerKey(clientId), RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);
    }

    public boolean acquireSession(DrcSession session) {
        saveSession(session);
        stringRedisTemplate.opsForValue().set(
                stateKey(session.getGeneration()), SessionState.ENTERING.name(),
                RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);

        Long acquired = stringRedisTemplate.execute(
                ACQUIRE_SESSION,
                List.of(
                        activeKey(session.getGatewaySn()),
                        clientSessionKey(session.getBrowserClientId()),
                        recoveryOwnerKey(session.getGatewaySn()),
                        recoveryBrowserKey(session.getGatewaySn())),
                session.getGeneration(),
                recoveryOwnerValue(session),
                recoveryBrowserValue(session),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        if (Long.valueOf(0).equals(acquired)) {
            deleteSessionMetadata(session.getGeneration());
            return false;
        }
        if (!Long.valueOf(1).equals(acquired)) {
            deleteSessionMetadata(session.getGeneration());
            throw new SecurityException("The DRC client is already bound to another session.");
        }
        return true;
    }

    public Optional<DrcSession> getSession(String gatewaySn) {
        String generation = stringRedisTemplate.opsForValue().get(activeKey(gatewaySn));
        return StringUtils.hasText(generation) ? getSessionByGeneration(generation) : Optional.empty();
    }

    /**
     * Loads the current session for a device event and reconstructs primary
     * metadata from the redundant lease markers when necessary. An active lease
     * with incomplete recovery metadata is an error, not "no session", because
     * acknowledging that event would strand control credentials permanently.
     */
    public Optional<DrcSession> getSessionForEvent(String gatewaySn) {
        String generation = stringRedisTemplate.opsForValue().get(activeKey(gatewaySn));
        if (!StringUtils.hasText(generation)) {
            return Optional.empty();
        }
        Optional<DrcSession> persisted = getSessionByGeneration(generation);
        if (persisted.isPresent()) {
            if (gatewaySn.equals(persisted.get().getGatewaySn())) {
                return persisted;
            }
            throw new IllegalStateException(
                    "Active DRC lease metadata belongs to a different gateway.");
        }

        String owner = stringRedisTemplate.opsForValue().get(recoveryOwnerKey(gatewaySn));
        String browser = stringRedisTemplate.opsForValue().get(recoveryBrowserKey(gatewaySn));
        String prefix = generation + "\n";
        if (!StringUtils.hasText(owner) || !owner.startsWith(prefix)
                || !StringUtils.hasText(browser) || !browser.startsWith(prefix)) {
            throw new IllegalStateException(
                    "Active DRC lease metadata is incomplete for gateway " + gatewaySn);
        }
        String ownerValue = owner.substring(prefix.length());
        int separator = ownerValue.indexOf('\n');
        String workspaceId = separator < 1 ? null : ownerValue.substring(0, separator);
        String userId = separator < 0 ? null : ownerValue.substring(separator + 1);
        String browserClientId = browser.substring(prefix.length());
        if (!StringUtils.hasText(workspaceId)
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(browserClientId)) {
            throw new IllegalStateException(
                    "Active DRC lease ownership is corrupt for gateway " + gatewaySn);
        }
        return Optional.of(DrcSession.builder()
                .gatewaySn(gatewaySn)
                .workspaceId(workspaceId)
                .userId(userId)
                .browserClientId(browserClientId)
                .deviceClientId(deviceClientId(gatewaySn, generation))
                .controlTopicSn(null)
                .generation(generation)
                .pausedJobId(stringRedisTemplate.opsForValue().get(pausedJobKey(generation)))
                .createdAt(0L)
                .deviceTimestampWatermark(parseLong(
                        stringRedisTemplate.opsForValue().get(watermarkKey(generation))))
                .build());
    }

    public Optional<DrcSession> getSessionByGeneration(String generation) {
        if (!StringUtils.hasText(generation)) {
            return Optional.empty();
        }
        Map<String, String> values = stringRedisTemplate.<String, String>opsForHash()
                .entries(sessionKey(generation));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            String gatewaySn = values.get(FIELD_GATEWAY);
            String workspaceId = values.get(FIELD_WORKSPACE);
            String userId = values.get(FIELD_USER);
            String browserClientId = values.get(FIELD_BROWSER_CLIENT);
            String deviceClientId = values.get(FIELD_DEVICE_CLIENT);
            String storedGeneration = values.get(FIELD_GENERATION);
            if (!StringUtils.hasText(gatewaySn)
                    || !StringUtils.hasText(workspaceId)
                    || !StringUtils.hasText(userId)
                    || !StringUtils.hasText(browserClientId)
                    || !StringUtils.hasText(deviceClientId)
                    || !generation.equals(storedGeneration)) {
                return Optional.empty();
            }
            return Optional.of(DrcSession.builder()
                    .gatewaySn(gatewaySn)
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .browserClientId(browserClientId)
                    .deviceClientId(deviceClientId)
                    .controlTopicSn(values.get(FIELD_CONTROL_TOPIC_SN))
                    .generation(storedGeneration)
                    .pausedJobId(values.get(FIELD_PAUSED_JOB))
                    .createdAt(Long.parseLong(values.getOrDefault(FIELD_CREATED_AT, "0")))
                    .deviceTimestampWatermark(parseLong(values.get(FIELD_DEVICE_WATERMARK)))
                    .build());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Rebuild a session whose primary metadata hash was lost, while proving
     * that the requesting browser owns the active gateway generation.
     */
    public Optional<DrcSession> recoverSessionForOwner(
            String gatewaySn, String workspaceId, String userId, String browserClientId) {
        assertBrowserClientOwner(workspaceId, userId, browserClientId);
        String generation = stringRedisTemplate.opsForValue().get(activeKey(gatewaySn));
        if (!StringUtils.hasText(generation)) {
            return Optional.empty();
        }
        if (!generation.equals(stringRedisTemplate.opsForValue().get(
                clientSessionKey(browserClientId)))
                || !recoveryOwnerValue(generation, workspaceId, userId).equals(
                stringRedisTemplate.opsForValue().get(recoveryOwnerKey(gatewaySn)))
                || !recoveryBrowserValue(generation, browserClientId).equals(
                stringRedisTemplate.opsForValue().get(recoveryBrowserKey(gatewaySn)))) {
            throw new SecurityException("The active DRC lease belongs to another owner.");
        }

        Optional<DrcSession> persisted = getSessionByGeneration(generation);
        if (persisted.filter(session -> gatewaySn.equals(session.getGatewaySn())
                && workspaceId.equals(session.getWorkspaceId())
                && userId.equals(session.getUserId())
                && browserClientId.equals(session.getBrowserClientId())
                && generation.equals(session.getGeneration())).isPresent()) {
            return persisted;
        }
        return Optional.of(DrcSession.builder()
                .gatewaySn(gatewaySn)
                .workspaceId(workspaceId)
                .userId(userId)
                .browserClientId(browserClientId)
                .deviceClientId(deviceClientId(gatewaySn, generation))
                .controlTopicSn(null)
                .generation(generation)
                .pausedJobId(stringRedisTemplate.opsForValue().get(pausedJobKey(generation)))
                .createdAt(0L)
                .deviceTimestampWatermark(parseLong(
                        stringRedisTemplate.opsForValue().get(watermarkKey(generation))))
                .build());
    }

    public String getActiveClient(String gatewaySn) {
        return getSession(gatewaySn).map(DrcSession::getBrowserClientId).orElse(null);
    }

    public Optional<SessionState> getState(DrcSession session) {
        String state = stringRedisTemplate.opsForValue().get(stateKey(session.getGeneration()));
        if (!StringUtils.hasText(state)) {
            return Optional.empty();
        }
        try {
            return Optional.of(SessionState.valueOf(state));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public void saveSession(DrcSession session) {
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_GATEWAY, session.getGatewaySn());
        values.put(FIELD_WORKSPACE, session.getWorkspaceId());
        values.put(FIELD_USER, session.getUserId());
        values.put(FIELD_BROWSER_CLIENT, session.getBrowserClientId());
        values.put(FIELD_DEVICE_CLIENT, session.getDeviceClientId());
        if (StringUtils.hasText(session.getControlTopicSn())) {
            values.put(FIELD_CONTROL_TOPIC_SN, session.getControlTopicSn());
        }
        values.put(FIELD_GENERATION, session.getGeneration());
        values.put(FIELD_CREATED_AT, Long.toString(session.getCreatedAt()));
        if (StringUtils.hasText(session.getPausedJobId())) {
            values.put(FIELD_PAUSED_JOB, session.getPausedJobId());
        }
        if (session.getDeviceTimestampWatermark() != null) {
            values.put(FIELD_DEVICE_WATERMARK,
                    Long.toString(session.getDeviceTimestampWatermark()));
        }
        String key = sessionKey(session.getGeneration());
        stringRedisTemplate.<String, String>opsForHash().putAll(key, values);
        stringRedisTemplate.expire(key, RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);
        if (StringUtils.hasText(session.getPausedJobId())) {
            stringRedisTemplate.opsForValue().set(
                    pausedJobKey(session.getGeneration()), session.getPausedJobId(),
                    RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);
        }
        if (session.getDeviceTimestampWatermark() != null) {
            stringRedisTemplate.opsForValue().set(
                    watermarkKey(session.getGeneration()),
                    Long.toString(session.getDeviceTimestampWatermark()),
                    RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS);
        }
    }

    public boolean markActive(DrcSession session) {
        return transitionState(session, SessionState.ENTERING, SessionState.ACTIVE);
    }

    public boolean beginExit(DrcSession session) {
        return prepareExit(session) != ExitPreparation.REJECTED;
    }

    public ExitPreparation prepareExit(DrcSession session) {
        if (session == null || !StringUtils.hasText(session.getGeneration())) {
            return ExitPreparation.REJECTED;
        }
        Long result = stringRedisTemplate.execute(
                PREPARE_EXIT,
                sessionKeys(session),
                session.getGeneration(), RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        if (Long.valueOf(1).equals(result)) {
            return ExitPreparation.STARTED_ACTIVE;
        }
        if (Long.valueOf(2).equals(result)) {
            return ExitPreparation.STARTED_UNCERTAIN;
        }
        if (Long.valueOf(3).equals(result)) {
            return ExitPreparation.STARTED_ENTERING;
        }
        if (Long.valueOf(4).equals(result)) {
            return ExitPreparation.RETRY_EXITING;
        }
        if (Long.valueOf(5).equals(result)) {
            return ExitPreparation.RECOVERED_UNKNOWN;
        }
        return ExitPreparation.REJECTED;
    }

    public boolean markUncertain(DrcSession session) {
        return transitionState(session, SessionState.ENTERING, SessionState.UNCERTAIN)
                || transitionState(session, SessionState.ACTIVE, SessionState.UNCERTAIN);
    }

    /**
     * Atomically reserves the current generation for disconnect-event cleanup.
     * While EVENT_CLEANING is held, HTTP exit cannot release the lease and a
     * new generation cannot start before the paused wayline is handled.
     * Returning true for an existing EVENT_CLEANING state permits protocol
     * redelivery after a previous cleanup attempt failed.
     */
    public boolean claimEventCleanup(DrcSession session) {
        if (session == null || !StringUtils.hasText(session.getGeneration())) {
            return false;
        }
        Long result = stringRedisTemplate.execute(
                CLAIM_EVENT_CLEANUP,
                sessionKeys(session),
                session.getGeneration(), RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        return Long.valueOf(1).equals(result) || Long.valueOf(2).equals(result);
    }

    public boolean restoreAfterFailedExit(DrcSession session, SessionState previousState) {
        if (previousState != SessionState.ACTIVE
                && previousState != SessionState.UNCERTAIN
                && previousState != SessionState.ENTERING) {
            return false;
        }
        return transitionState(session, SessionState.EXITING, previousState);
    }

    public boolean isCurrent(DrcSession session) {
        return session != null && session.getGeneration().equals(
                stringRedisTemplate.opsForValue().get(activeKey(session.getGatewaySn())));
    }

    public void refreshSession(DrcSession session) {
        Long refreshed = stringRedisTemplate.execute(
                REFRESH_SESSION,
                sessionKeys(session),
                session.getGeneration(), RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        if (!Long.valueOf(1).equals(refreshed)) {
            throw new IllegalStateException("The DRC session is no longer active.");
        }
    }

    /**
     * Atomically transfers an active lease to a fresh browser MQTT identity
     * owned by the same authenticated principal. The previous browser ACL is
     * revoked in the same Redis operation, so two refreshed tabs cannot retain
     * command publish permission at the same time.
     */
    public boolean rebindBrowserClient(DrcSession session, String replacementClientId) {
        if (session == null || !StringUtils.hasText(session.getGeneration())
                || !StringUtils.hasText(replacementClientId)) {
            return false;
        }
        assertBrowserClientOwner(
                session.getWorkspaceId(), session.getUserId(), replacementClientId);
        String previousClientId = session.getBrowserClientId();
        if (replacementClientId.equals(previousClientId)) {
            return true;
        }

        Long rebound = stringRedisTemplate.execute(
                REBIND_BROWSER_CLIENT,
                List.of(
                        activeKey(session.getGatewaySn()),
                        stateKey(session.getGeneration()),
                        sessionKey(session.getGeneration()),
                        clientSessionKey(previousClientId),
                        clientSessionKey(replacementClientId),
                        recoveryBrowserKey(session.getGatewaySn()),
                        ownerKey(replacementClientId),
                        ownerKey(previousClientId),
                        aclKey(previousClientId)),
                session.getGeneration(),
                previousClientId,
                replacementClientId,
                recoveryBrowserValue(session.getGeneration(), previousClientId),
                recoveryBrowserValue(session.getGeneration(), replacementClientId),
                ownerToken(session.getWorkspaceId(), session.getUserId()),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString(),
                FIELD_BROWSER_CLIENT);
        if (Long.valueOf(1).equals(rebound)) {
            session.setBrowserClientId(replacementClientId);
            return true;
        }
        if (Long.valueOf(-1).equals(rebound)) {
            throw new IllegalStateException("The DRC session is currently changing state.");
        }
        if (Long.valueOf(-2).equals(rebound)) {
            throw new SecurityException("The replacement DRC client is already in use.");
        }
        if (Long.valueOf(-3).equals(rebound)) {
            throw new SecurityException("The replacement DRC client owner changed.");
        }
        return false;
    }

    /**
     * Revoke both MQTT identities before releasing the gateway lease. This
     * prevents a new generation from racing with cleanup of reused keys.
     */
    public boolean releaseSession(DrcSession session) {
        if (session == null || !StringUtils.hasText(session.getGeneration())) {
            return false;
        }
        revokeSessionAcls(session);
        stringRedisTemplate.expire(
                ownerKey(session.getBrowserClientId()),
                RedisConst.DRC_MODE_ALIVE_SECOND,
                TimeUnit.SECONDS);
        compareDelete(clientSessionKey(session.getBrowserClientId()), session.getGeneration());
        compareDelete(recoveryOwnerKey(session.getGatewaySn()), recoveryOwnerValue(session));
        compareDelete(recoveryBrowserKey(session.getGatewaySn()), recoveryBrowserValue(session));
        deleteSessionMetadata(session.getGeneration());
        return compareDelete(activeKey(session.getGatewaySn()), session.getGeneration());
    }

    public void revokeSessionAcls(DrcSession session) {
        deleteClientAcl(session.getBrowserClientId());
        deleteClientAcl(session.getDeviceClientId());
    }

    public boolean clearSessionForEvent(String gatewaySn, Long eventTimestamp) {
        Optional<DrcSession> sessionOpt = getSession(gatewaySn);
        if (sessionOpt.isEmpty()) {
            return false;
        }
        DrcSession session = sessionOpt.get();
        if (eventTimestamp == null
                || session.getDeviceTimestampWatermark() == null
                || eventTimestamp < session.getDeviceTimestampWatermark()) {
            return false;
        }
        return releaseSession(session);
    }

    public void clearSession(String gatewaySn) {
        getSession(gatewaySn).ifPresent(this::releaseSession);
    }

    public boolean hasClientAcl(String clientId) {
        return StringUtils.hasText(clientId)
                && Boolean.TRUE.equals(stringRedisTemplate.hasKey(aclKey(clientId)));
    }

    public void grantDeviceTopics(String clientId, String downTopic, String upTopic) {
        String key = aclKey(clientId);
        // EMQX expects raw Redis hash values. A generic RedisTemplate JSON-encodes
        // these strings as '\"publish\"', which EMQX rejects as an invalid rule.
        stringRedisTemplate.opsForHash().put(key, upTopic, MqttAclAccessEnum.PUB.getValue());
        stringRedisTemplate.opsForHash().put(key, downTopic, MqttAclAccessEnum.SUB.getValue());
        expireClientAcl(clientId);
    }

    public void grantUserTopics(String clientId, String downTopic, String upTopic) {
        String key = aclKey(clientId);
        stringRedisTemplate.opsForHash().put(key, downTopic, MqttAclAccessEnum.PUB.getValue());
        stringRedisTemplate.opsForHash().put(key, upTopic, MqttAclAccessEnum.SUB.getValue());
        expireClientAcl(clientId);
    }

    public void grantUserSubscribeTopic(String clientId, String topic) {
        String key = aclKey(clientId);
        stringRedisTemplate.opsForHash().put(key, topic, MqttAclAccessEnum.SUB.getValue());
        expireClientAcl(clientId);
    }

    public boolean expireClientAcl(String clientId) {
        return StringUtils.hasText(clientId) && Boolean.TRUE.equals(stringRedisTemplate.expire(
                aclKey(clientId), RedisConst.DRC_MODE_ALIVE_SECOND, TimeUnit.SECONDS));
    }

    public boolean deleteClientAcl(String clientId) {
        return StringUtils.hasText(clientId)
                && Boolean.TRUE.equals(stringRedisTemplate.delete(aclKey(clientId)));
    }

    private boolean transitionState(DrcSession session, SessionState expected, SessionState next) {
        Long result = stringRedisTemplate.execute(
                TRANSITION_STATE,
                sessionKeys(session),
                session.getGeneration(), expected.name(), next.name(),
                RedisConst.DRC_MODE_ALIVE_SECOND.toString());
        return Long.valueOf(1).equals(result);
    }

    private void deleteSessionMetadata(String generation) {
        stringRedisTemplate.delete(List.of(
                sessionKey(generation), stateKey(generation),
                pausedJobKey(generation), watermarkKey(generation)));
    }

    private boolean compareDelete(String key, String expected) {
        Long result = stringRedisTemplate.execute(
                COMPARE_DELETE, List.of(key), expected);
        return Long.valueOf(1).equals(result);
    }

    private List<String> sessionKeys(DrcSession session) {
        return List.of(
                activeKey(session.getGatewaySn()),
                stateKey(session.getGeneration()),
                sessionKey(session.getGeneration()),
                clientSessionKey(session.getBrowserClientId()),
                ownerKey(session.getBrowserClientId()),
                aclKey(session.getBrowserClientId()),
                aclKey(session.getDeviceClientId()),
                recoveryOwnerKey(session.getGatewaySn()),
                recoveryBrowserKey(session.getGatewaySn()),
                pausedJobKey(session.getGeneration()),
                watermarkKey(session.getGeneration()));
    }

    public String deviceClientId(String gatewaySn, String generation) {
        return gatewaySn + "-" + generation;
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String ownerToken(String workspaceId, String userId) {
        return workspaceId + "\n" + userId;
    }

    private String activeKey(String gatewaySn) {
        return RedisConst.DRC_PREFIX + gatewaySn;
    }

    private String sessionKey(String generation) {
        return SESSION_PREFIX + generation;
    }

    private String stateKey(String generation) {
        return STATE_PREFIX + generation;
    }

    private String ownerKey(String clientId) {
        return OWNER_PREFIX + clientId;
    }

    private String clientSessionKey(String clientId) {
        return CLIENT_SESSION_PREFIX + clientId;
    }

    private String recoveryOwnerKey(String gatewaySn) {
        return RECOVERY_OWNER_PREFIX + gatewaySn;
    }

    private String recoveryBrowserKey(String gatewaySn) {
        return RECOVERY_BROWSER_PREFIX + gatewaySn;
    }

    private String pausedJobKey(String generation) {
        return PAUSED_JOB_PREFIX + generation;
    }

    private String watermarkKey(String generation) {
        return WATERMARK_PREFIX + generation;
    }

    private String recoveryOwnerValue(DrcSession session) {
        return recoveryOwnerValue(
                session.getGeneration(), session.getWorkspaceId(), session.getUserId());
    }

    private String recoveryOwnerValue(String generation, String workspaceId, String userId) {
        return generation + "\n" + ownerToken(workspaceId, userId);
    }

    private String recoveryBrowserValue(DrcSession session) {
        return recoveryBrowserValue(session.getGeneration(), session.getBrowserClientId());
    }

    private String recoveryBrowserValue(String generation, String browserClientId) {
        return generation + "\n" + browserClientId;
    }

    private String aclKey(String clientId) {
        return RedisConst.MQTT_ACL_PREFIX + clientId;
    }
}
