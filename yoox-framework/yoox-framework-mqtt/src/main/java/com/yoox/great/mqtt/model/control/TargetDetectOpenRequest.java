package com.yoox.great.mqtt.model.control;

import com.yoox.great.context.base.BaseModel;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

public class TargetDetectOpenRequest extends BaseModel {

    @NotNull
    @Min(0)
    @Max(1)
    private Integer aiLensType;

    @NotNull
    @Min(0)
    @Max(0)
    private Integer sceneType;

    private List<Integer> targetTypeList;

    public Integer getAiLensType() {
        return aiLensType;
    }

    public TargetDetectOpenRequest setAiLensType(Integer aiLensType) {
        this.aiLensType = aiLensType;
        return this;
    }

    public Integer getSceneType() {
        return sceneType;
    }

    public TargetDetectOpenRequest setSceneType(Integer sceneType) {
        this.sceneType = sceneType;
        return this;
    }

    public List<Integer> getTargetTypeList() {
        return targetTypeList;
    }

    public TargetDetectOpenRequest setTargetTypeList(List<Integer> targetTypeList) {
        this.targetTypeList = targetTypeList;
        return this;
    }
}
