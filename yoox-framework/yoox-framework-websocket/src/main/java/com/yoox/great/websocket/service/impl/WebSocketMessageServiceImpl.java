package com.yoox.great.websocket.service.impl;

import com.yoox.great.websocket.dto.MyConcurrentWebSocketSession;
import com.yoox.great.websocket.dto.WebSocketMessageResponse;
import com.yoox.great.websocket.service.IWebSocketManageService;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

@Service
@Slf4j
public class WebSocketMessageServiceImpl implements IWebSocketMessageService {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private IWebSocketManageService webSocketManageService;

    @Override
    public void sendMessage(MyConcurrentWebSocketSession session, WebSocketMessageResponse message) {
        if (session == null) {
            return;
        }
        try {
            if (!session.isOpen()) {
                session.close();
                log.debug("This session is closed.");
                return;
            }


            session.sendMessage(new TextMessage(mapper.writeValueAsBytes(message)));
        } catch (IOException e) {
            log.info("Failed to publish the message. {}", message.toString());
            e.printStackTrace();
        }
    }

    @Override
    public void sendBatch(Collection<MyConcurrentWebSocketSession> sessions, WebSocketMessageResponse message) {
        if (sessions.isEmpty()) {
            return;
        }

        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(message);
        } catch (IOException exception) {
            log.warn("Failed to serialize WebSocket batch message. bizCode={}", message.getBizCode(), exception);
            return;
        }

        int delivered = 0;
        int skipped = 0;
        for (MyConcurrentWebSocketSession session : sessions) {
            try {
                if (!session.isOpen()) {
                    session.close();
                    skipped++;
                    log.debug("Skipping closed WebSocket session. ID: {}", session.getId());
                    continue;
                }
                session.sendMessage(new TextMessage(payload));
                delivered++;
            } catch (IOException | RuntimeException exception) {
                skipped++;
                log.warn("Failed to publish WebSocket message to session {}. bizCode={}",
                        session.getId(), message.getBizCode(), exception);
            }
        }
        log.debug("WebSocket batch completed. bizCode={}, targets={}, delivered={}, skipped={}",
                message.getBizCode(), sessions.size(), delivered, skipped);
    }

    @Override
    public void sendBatch(String workspaceId, Integer userType, String bizCode, Object data) {
        if (!StringUtils.hasText(workspaceId)) {
            throw new RuntimeException("Workspace ID does not exist.");
        }
        Collection<MyConcurrentWebSocketSession> sessions = Objects.isNull(userType) ?
                webSocketManageService.getValueWithWorkspace(workspaceId) :
                webSocketManageService.getValueWithWorkspaceAndUserType(workspaceId, userType);

        this.sendBatch(sessions, new WebSocketMessageResponse()
                .setData(Objects.requireNonNullElse(data, ""))
                .setTimestamp(System.currentTimeMillis())
                .setBizCode(bizCode));
    }

    @Override
    public void sendBatch(String workspaceId, String bizCode, Object data) {
        this.sendBatch(workspaceId, null, bizCode, data);
    }
}
