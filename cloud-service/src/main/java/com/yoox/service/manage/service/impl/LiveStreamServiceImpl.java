package com.yoox.service.manage.service.impl;

import com.yoox.api.livestream.AbstractLivestreamService;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.mqtt.enums.livestream.LiveErrorCodeEnum;
import com.yoox.great.mqtt.enums.livestream.UrlTypeEnum;
import com.yoox.great.mqtt.model.device.VideoId;
import com.yoox.great.mqtt.model.livestream.*;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.service.manage.model.dto.*;
import com.yoox.service.manage.model.param.DeviceQueryParam;
import com.yoox.service.manage.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class LiveStreamServiceImpl implements ILiveStreamService {

    @Autowired
    private ICapacityCameraService capacityCameraService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IWorkspaceService workspaceService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private AbstractLivestreamService abstractLivestreamService;

    @Override
    public List<CapacityDeviceDTO> getLiveCapacity(String workspaceId) {
        List<DeviceDTO> devicesList = deviceService.getDevicesByParams(
                DeviceQueryParam.builder()
                        .workspaceId(workspaceId)
                        .domains(List.of(DeviceDomainEnum.DRONE.getDomain(), DeviceDomainEnum.DOCK.getDomain()))
                        .build());

        return devicesList.stream()
                .filter(device -> deviceRedisService.checkDeviceOnline(device.getDeviceSn()))
                .map(device -> CapacityDeviceDTO.builder()
                        .name(Objects.requireNonNullElse(device.getNickname(), device.getDeviceName()))
                        .sn(device.getDeviceSn())
                        .camerasList(capacityCameraService.getCapacityCameraByDeviceSn(device.getDeviceSn()))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public HttpResultResponse liveStart(LiveTypeDTO liveParam) {
        if (liveParam == null || liveParam.getUrlType() != UrlTypeEnum.RTSP) {
            return HttpResultResponse.error(
                    LiveErrorCodeEnum.URL_TYPE_NOT_SUPPORTED.getCode(),
                    "YOOX Cloud GCS P0 only supports RTSP live streaming.");
        }

        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(liveParam.getVideoId());
        if (HttpResultResponse.CODE_SUCCESS != responseResult.getCode()) {
            return responseResult;
        }

        ILivestreamUrl url = LiveStreamProperty.get(liveParam.getUrlType());

        url = setExt(url, liveParam.getVideoId());

        // 支持调用方自定义推流地址：LiveTypeDTO.url 非空时覆盖配置生成的推流地址下发给设备
        boolean customPushUrl = liveParam.getUrl() != null && !liveParam.getUrl().trim().isEmpty();
        String pushUrl = customPushUrl ? liveParam.getUrl().trim() : url.toString();

        LiveStartPushRequest3 request3 = new LiveStartPushRequest3();
        request3.setUrl(pushUrl);
        request3.setUrl_type(liveParam.getUrlType().getType());
        request3.setVideo_id(liveParam.getVideoId().getDroneSn() + "-" + liveParam.getVideoId().getPayloadIndex().toString());
        request3.setVideo_quality(liveParam.getVideoQuality().getQuality());
        if (liveParam.getVideoType() != null) {
            request3.setVideo_type(liveParam.getVideoType().getType());
        }

        TopicServicesResponse<ServicesReplyData<String>> response = abstractLivestreamService.liveStartPush3(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()),
                request3);

        log.info("发送 RTSP 直播指令: video_id={}, video_type={}",
                request3.getVideo_id(), request3.getVideo_type());
        log.info("设备返回: result={}, info={}, output={}",
                response.getData().getResult(),
                response.getData().getInfo() == null ? null : "<RTSP URL>",
                response.getData().getOutput());
        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        LiveDTO live = new LiveDTO();

        LivestreamRtspUrl rtspPlayback = (LivestreamRtspUrl) url;
        String deviceRtspUrl = response.getData().getInfo();
        if (rtspPlayback.getPayUrl() != null && !rtspPlayback.getPayUrl().trim().isEmpty()) {
            // Devices publish RTSP to MediaMTX; browsers consume the matching WHEP endpoint.
            live.setUrl(rtspPlayback.getPayUrl());
        } else if (deviceRtspUrl != null && !deviceRtspUrl.trim().isEmpty()) {
            live.setUrl(deviceRtspUrl);
        } else if (pushUrl.regionMatches(true, 0, "rtsp://", 0, 7)) {
            live.setUrl(pushUrl);
        } else {
            return HttpResultResponse.error(
                    LiveErrorCodeEnum.FUNCTION_NOT_SUPPORT.getCode(),
                    "The device did not return an RTSP playback URL.");
        }

        return HttpResultResponse.success(live);
    }

    @Override
    public HttpResultResponse liveStop(VideoId videoId) {
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(videoId);
        if (HttpResultResponse.CODE_SUCCESS != responseResult.getCode()) {
            return responseResult;
        }

        TopicServicesResponse<ServicesReplyData> response = abstractLivestreamService.liveStopPush(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()), new LiveStopPushRequest()
                        .setVideoId(videoId));
        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse liveSetQuality(LiveTypeDTO liveParam) {
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(liveParam.getVideoId());
        if (responseResult.getCode() != 0) {
            return responseResult;
        }

        TopicServicesResponse<ServicesReplyData> response = abstractLivestreamService.liveSetQuality(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()), new LiveSetQualityRequest()
                        .setVideoQuality(liveParam.getVideoQuality())
                        .setVideoId(liveParam.getVideoId()));
        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse liveLensChange(LiveTypeDTO liveParam) {
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(liveParam.getVideoId());
        if (HttpResultResponse.CODE_SUCCESS != responseResult.getCode()) {
            return responseResult;
        }

        TopicServicesResponse<ServicesReplyData> response = abstractLivestreamService.liveLensChange(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()), new LiveLensChangeRequest()
                        .setVideoType(liveParam.getVideoType())
                        .setVideoId(liveParam.getVideoId()));

        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        return HttpResultResponse.success();
    }

    private HttpResultResponse<DeviceDTO> checkBeforeLive(VideoId videoId) {
        if (Objects.isNull(videoId)) {
            return HttpResultResponse.error(LiveErrorCodeEnum.ERROR_PARAMETERS);
        }

        Optional<DeviceDTO> deviceOpt = deviceService.getDeviceBySn(videoId.getDroneSn());
        if (deviceOpt.isEmpty()) {
            return HttpResultResponse.error(LiveErrorCodeEnum.NO_AIRCRAFT);
        }

        if (DeviceDomainEnum.DOCK == deviceOpt.get().getDomain()) {
            return HttpResultResponse.success(deviceOpt.get());
        }
        List<DeviceDTO> gatewayList = deviceService.getDevicesByParams(
                DeviceQueryParam.builder()
                        .childSn(videoId.getDroneSn())
                        .build());
        if (gatewayList.isEmpty()) {
            return HttpResultResponse.error(LiveErrorCodeEnum.NO_FLIGHT_CONTROL);
        }

        return HttpResultResponse.success(gatewayList.get(0));
    }

    /**
     * This is business-customized logic and is only used for testing.
     *
     * @param type
     * @param url
     * @param videoId
     */
    private ILivestreamUrl setExt(ILivestreamUrl url, VideoId videoId) {
        LivestreamRtspUrl rtspUrl = (LivestreamRtspUrl) url.clone();
        String streamName = videoId.getDroneSn() + "-" + videoId.getPayloadIndex();
        String rtspPrefix = rtspUrl.getUrl();
        if (rtspPrefix != null && !rtspPrefix.trim().isEmpty()) {
            rtspUrl.setUrl(rtspPrefix.endsWith("/")
                    ? rtspPrefix + streamName
                    : rtspPrefix + "/" + streamName);
        }
        if (rtspUrl.getPayUrl() != null && !rtspUrl.getPayUrl().trim().isEmpty()) {
            rtspUrl.setPayUrl(String.format(rtspUrl.getPayUrl(), streamName));
        }
        return rtspUrl;
    }
}
