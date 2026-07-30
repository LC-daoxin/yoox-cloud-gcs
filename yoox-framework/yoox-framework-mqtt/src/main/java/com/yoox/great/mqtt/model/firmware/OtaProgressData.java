package com.yoox.great.mqtt.model.firmware;

import com.yoox.great.mqtt.enums.firmware.OtaProgressStepEnum;

public class OtaProgressData {

    private Integer percent;

    private OtaProgressStepEnum currentStep;

    public OtaProgressData() {
    }

    @Override
    public String toString() {
        return "OtaProgressData{" +
                "percent=" + percent +
                ", currentStep=" + currentStep +
                '}';
    }

    public Integer getPercent() {
        return percent;
    }

    public OtaProgressData setPercent(Integer percent) {
        this.percent = percent;
        return this;
    }

    public OtaProgressStepEnum getCurrentStep() {
        return currentStep;
    }

    public OtaProgressData setCurrentStep(OtaProgressStepEnum currentStep) {
        this.currentStep = currentStep;
        return this;
    }
}
