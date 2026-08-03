package com.yoox.service.control.service;

import com.yoox.great.mqtt.property.DrcModeMqttBroker;
import com.yoox.service.control.model.dto.JwtAclDTO;
import com.yoox.service.control.model.param.DrcConnectParam;
import com.yoox.service.control.model.param.DrcModeParam;

public interface IDrcService {

    DrcModeMqttBroker userDrcAuth(String workspaceId, String userId, String username, DrcConnectParam param);

    JwtAclDTO deviceDrcEnter(String workspaceId, String userId, DrcModeParam param);

    void deviceDrcExit(String workspaceId, String userId, DrcModeParam param);
}
