package com.yoox.service.manage.service.impl;

import com.yoox.api.livestream.AbstractLivestreamService;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.exception.CloudSDKException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class LiveStreamServiceImpl implements ILiveStreamService {

    private static final long LIVE_START_COOLDOWN_MS = 30_000L;

    private static final HttpClient MEDIA_MTX_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    /**
     * The cockpit can be open in more than one browser tab. Serialize starts by
     * stream and remember a recent accepted command so a slow encoder does not
     * receive overlapping live_start_push retries from different tabs.
     */
    private final Map<String, ReentrantLock> liveStartLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> acceptedLiveStarts = new ConcurrentHashMap<>();

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

    @Value("${livestream.mediamtx-metrics-url:http://mediamtx:9998/metrics}")
    private String mediaMtxMetricsUrl;

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
                    "Only RTSP live streaming is enabled in the current runtime.");
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
        String streamName = streamName(liveParam.getVideoId());
        String configuredPlaybackUrl = getConfiguredPlaybackUrl(url);
        boolean canReusePublisher = liveParam.getUrlType() == UrlTypeEnum.RTSP &&
                !customPushUrl &&
                configuredPlaybackUrl != null;

        ReentrantLock startLock = liveStartLocks.computeIfAbsent(streamName, ignored -> new ReentrantLock());
        startLock.lock();
        try {
            if (canReusePublisher && isMediaPublisherReady(streamName)) {
                return reusedLiveResponse(streamName, configuredPlaybackUrl, false);
            }
            Long acceptedAt = acceptedLiveStarts.get(streamName);
            if (canReusePublisher && acceptedAt != null &&
                    System.currentTimeMillis() - acceptedAt < LIVE_START_COOLDOWN_MS) {
                log.info("直播启动指令刚被受理，等待设备编码器产生媒体帧，不重复下发: stream={}", streamName);
                return reusedLiveResponse(streamName, configuredPlaybackUrl, false);
            }

            LiveStartPushRequest3 request3 = new LiveStartPushRequest3();
            request3.setUrl(pushUrl);
            request3.setUrl_type(liveParam.getUrlType().getType());
            // Autel's live_start_push protocol is an exception to the regular VideoId
            // wire format: firmware expects "droneSn-payloadIndex" here. Stop, quality
            // and lens-change commands still use the SDK's slash-delimited VideoId.
            request3.setVideo_id(streamName);
            request3.setVideo_quality(liveParam.getVideoQuality().getQuality());
            if (liveParam.getVideoType() != null) {
                request3.setVideo_type(liveParam.getVideoType().getType());
            }

            TopicServicesResponse<ServicesReplyData<String>> response;
            try {
                response = abstractLivestreamService.liveStartPush3(
                        SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()),
                        request3);
            } catch (CloudSDKException exception) {
                // Another page or a concurrent request may have started publishing while
                // this MQTT request was waiting for a reply. Treat the ready publisher
                // as the source of truth instead of surfacing a false 211001 failure.
                if (canReusePublisher && isMediaPublisherReady(streamName)) {
                    acceptedLiveStarts.put(streamName, System.currentTimeMillis());
                    log.info("MQTT 启动直播未收到回复，但媒体流已就绪，按复用成功处理: stream={}", streamName);
                    return reusedLiveResponse(streamName, configuredPlaybackUrl, true);
                }
                throw exception;
            }

            log.info("发送 RTSP 直播指令: video_id={}, video_type={}",
                    request3.getVideo_id(), request3.getVideo_type());
            log.info("设备返回: result={}, info={}, output={}",
                    response.getData().getResult(),
                    response.getData().getInfo() == null ? null : "<RTSP URL>",
                    response.getData().getOutput());
            if (!response.getData().getResult().isSuccess()) {
                if (canReusePublisher && isMediaPublisherReady(streamName)) {
                    acceptedLiveStarts.put(streamName, System.currentTimeMillis());
                    log.info("设备返回直播启动失败，但媒体流已就绪，按复用成功处理: stream={}", streamName);
                    return reusedLiveResponse(streamName, configuredPlaybackUrl, true);
                }
                return HttpResultResponse.error(response.getData().getResult());
            }
            acceptedLiveStarts.put(streamName, System.currentTimeMillis());

            LiveDTO live = new LiveDTO();
            live.setReused(false);
            live.setStartedByRequest(true);

            String deviceRtspUrl = response.getData().getInfo();
            if (configuredPlaybackUrl != null && !configuredPlaybackUrl.trim().isEmpty()) {
                // Devices publish RTSP to MediaMTX; browsers consume the matching WHEP endpoint.
                live.setUrl(configuredPlaybackUrl);
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
        } finally {
            startLock.unlock();
        }
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

        acceptedLiveStarts.remove(streamName(videoId));

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
        String streamName = streamName(videoId);
        if (url instanceof LivestreamRtmpUrl configured) {
            LivestreamRtmpUrl rtmpUrl = configured.clone();
            String prefix = rtmpUrl.getUrl();
            if (prefix != null && !prefix.trim().isEmpty()) {
                int queryIndex = prefix.indexOf('?');
                String base = queryIndex < 0 ? prefix : prefix.substring(0, queryIndex);
                String query = queryIndex < 0 ? "" : prefix.substring(queryIndex);
                rtmpUrl.setUrl((base.endsWith("/") ? base + streamName : base + "/" + streamName) + query);
            }
            if (rtmpUrl.getPayUrl() != null && !rtmpUrl.getPayUrl().trim().isEmpty()) {
                rtmpUrl.setPayUrl(String.format(rtmpUrl.getPayUrl(), streamName));
            }
            return rtmpUrl;
        }
        LivestreamRtspUrl rtspUrl = (LivestreamRtspUrl) url.clone();
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

    private String streamName(VideoId videoId) {
        return videoId.getDroneSn() + "-" + videoId.getPayloadIndex();
    }

    private String getConfiguredPlaybackUrl(ILivestreamUrl url) {
        String playbackUrl = url instanceof LivestreamRtspUrl rtsp
                ? rtsp.getPayUrl()
                : url instanceof LivestreamRtmpUrl rtmp
                    ? rtmp.getPayUrl()
                    : null;
        return playbackUrl == null || playbackUrl.trim().isEmpty() ? null : playbackUrl;
    }

    private HttpResultResponse<LiveDTO> reusedLiveResponse(
            String streamName, String playbackUrl, boolean startedByRequest) {
        LiveDTO live = new LiveDTO();
        live.setUrl(playbackUrl);
        live.setReused(true);
        live.setStartedByRequest(startedByRequest);
        log.info("复用 MediaMTX 发布流: stream={}, startedByRequest={}",
                streamName, startedByRequest);
        return HttpResultResponse.success(live);
    }

    private boolean isMediaPublisherReady(String streamName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mediaMtxMetricsUrl))
                    .timeout(Duration.ofMillis(1_500))
                    .GET()
                    .build();
            HttpResponse<String> response = MEDIA_MTX_HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("查询 MediaMTX 指标失败: stream={}, status={}",
                        streamName, response.statusCode());
                return false;
            }
            String labels = "{name=\"" + escapePrometheusLabel(streamName) +
                    "\",state=\"ready\"}";
            String metrics = response.body();
            boolean publisherReady = hasPositiveMetric(metrics, "paths" + labels);
            boolean mediaReceived = hasPositiveMetric(metrics, "paths_inbound_bytes" + labels) ||
                    hasPositiveMetric(metrics, "paths_bytes_received" + labels);
            if (publisherReady && !mediaReceived) {
                log.warn("MediaMTX 发布会话已建立但尚未收到媒体帧，不按可复用流处理: stream={}", streamName);
            }
            return publisherReady && mediaReceived;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("查询 MediaMTX 发布流被中断: stream={}", streamName);
            return false;
        } catch (Exception exception) {
            log.warn("查询 MediaMTX 发布流异常，将继续按设备启动流程处理: stream={}, error={}",
                    streamName, exception.getMessage());
            return false;
        }
    }

    private boolean hasPositiveMetric(String metrics, String metricPrefix) {
        return metrics.lines()
                .filter(line -> line.startsWith(metricPrefix))
                .map(line -> line.substring(metricPrefix.length()).trim())
                .anyMatch(value -> {
                    try {
                        return Double.parseDouble(value) > 0;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                });
    }

    private String escapePrometheusLabel(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
