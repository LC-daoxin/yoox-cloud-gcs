package com.yoox.service.manage.service.impl;

import com.yoox.api.livestream.AbstractLivestreamService;
import com.yoox.great.mqtt.model.livestream.DockLivestreamAbilityUpdate;
import com.yoox.great.mqtt.model.livestream.RcLivestreamAbilityUpdate;
import com.yoox.great.mqtt.handle.state.TopicStateRequest;
import com.yoox.service.manage.model.receiver.CapacityDeviceReceiver;
import com.yoox.service.manage.service.ICapacityCameraService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SDKLivestreamService extends AbstractLivestreamService {

    @Autowired
    private ICapacityCameraService capacityCameraService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void dockLivestreamAbilityUpdate(TopicStateRequest<DockLivestreamAbilityUpdate> request, MessageHeaders headers) {
        saveLiveCapacity(request.getData().getLiveCapacity().getDeviceList());
    }

    @Override
    public void rcLivestreamAbilityUpdate(TopicStateRequest<RcLivestreamAbilityUpdate> request, MessageHeaders headers) {
        saveLiveCapacity(request.getData().getLiveCapacity().getDeviceList());
    }

    private void saveLiveCapacity(Object data) {
        List<CapacityDeviceReceiver> devices = objectMapper.convertValue(
                data, new TypeReference<List<CapacityDeviceReceiver>>() {
                });
        for (CapacityDeviceReceiver capacityDeviceReceiver : devices) {
            capacityCameraService.saveCapacityCameraReceiverList(
                    capacityDeviceReceiver.getCameraList(), capacityDeviceReceiver.getSn());
        }
    }
}
