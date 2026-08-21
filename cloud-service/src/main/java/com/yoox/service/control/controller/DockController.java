package com.yoox.service.control.controller;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.enums.RemoteDebugMethodEnum;
import com.yoox.service.control.model.param.*;
import com.yoox.service.control.service.IControlService;
import com.yoox.service.control.service.impl.ControlAccessService;
import com.yoox.great.mqtt.model.control.TargetDetectOpenRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@Slf4j
@RequestMapping("${url.control.prefix}${url.control.version}/devices")
public class DockController {

    @Autowired
    private IControlService controlService;

    @Autowired
    private ControlAccessService controlAccessService;

    @PostMapping("/{sn}/jobs/{service_identifier}")
    public HttpResultResponse createControlJob(@PathVariable String sn,
                                               @PathVariable("service_identifier") String serviceIdentifier,
                                               @Valid @RequestBody(required = false) RemoteDebugParam param,
                                               HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.controlDockDebug(sn, RemoteDebugMethodEnum.find(serviceIdentifier), param);
    }

    @PostMapping("/{sn}/jobs/fly-to-point")
    public HttpResultResponse flyToPoint(@PathVariable String sn, @Valid @RequestBody FlyToPointParam param,
                                         HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.flyToPoint(sn, param);
    }

    @DeleteMapping({"/{sn}/jobs/fly-to-point", "/{sn}/jobs/point-flight"})
    public HttpResultResponse flyToPointStop(@PathVariable String sn, HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.flyToPointStop(sn);
    }

    @GetMapping("/{sn}/jobs/point-flight/status")
    public HttpResultResponse getPointFlightState(@PathVariable String sn, HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.getPointFlightState(sn);
    }

    @PostMapping("/{sn}/jobs/takeoff-to-point")
    public HttpResultResponse takeoffToPoint(@PathVariable String sn, @Valid @RequestBody TakeoffToPointParam param,
                                             HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.takeoffToPoint(sn, param);
    }

    @PostMapping("/{sn}/authority/flight")
    public HttpResultResponse seizeFlightAuthority(@PathVariable String sn, HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        // This endpoint represents an explicit operator action. Always dispatch
        // flight_authority_grab instead of trusting a potentially stale A cache.
        return controlService.seizeAuthority(sn, DroneAuthorityEnum.FLIGHT, null, true);
    }

    @PostMapping("/{sn}/authority/payload")
    public HttpResultResponse seizePayloadAuthority(@PathVariable String sn, @Valid @RequestBody DronePayloadParam param,
                                                     HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.seizeAuthority(sn, DroneAuthorityEnum.PAYLOAD, param);
    }

    @PostMapping("/{sn}/payload/commands")
    public HttpResultResponse payloadCommands(@PathVariable String sn, @Valid @RequestBody PayloadCommandsParam param,
                                              HttpServletRequest request) throws Exception {
        controlAccessService.requireDevice(request, sn);
        param.setSn(sn);
        return controlService.payloadCommands(param);
    }

    @PostMapping("/{sn}/target-detection")
    public HttpResultResponse openTargetDetection(
            @PathVariable String sn,
            @Valid @RequestBody TargetDetectOpenRequest param,
            HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.openTargetDetection(sn, param);
    }

    @DeleteMapping("/{sn}/target-detection")
    public HttpResultResponse closeTargetDetection(@PathVariable String sn, HttpServletRequest request) {
        controlAccessService.requireDevice(request, sn);
        return controlService.closeTargetDetection(sn);
    }


}
