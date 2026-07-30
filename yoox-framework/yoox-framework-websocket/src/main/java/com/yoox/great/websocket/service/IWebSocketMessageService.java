package com.yoox.great.websocket.service;


import com.yoox.great.websocket.dto.MyConcurrentWebSocketSession;
import com.yoox.great.websocket.dto.WebSocketMessageResponse;

import java.util.Collection;

public interface IWebSocketMessageService {

    void sendMessage(MyConcurrentWebSocketSession session, WebSocketMessageResponse message);

    void sendBatch(Collection<MyConcurrentWebSocketSession> sessions, WebSocketMessageResponse message);

    void sendBatch(String workspaceId, Integer userType, String bizCode, Object data);

    void sendBatch(String workspaceId, String bizCode, Object data);
}
