package com.yoox.great.mqtt.model.livestream;

import com.fasterxml.jackson.annotation.JsonValue;

public interface ILivestreamUrl {

    @JsonValue
    String toString();

    ILivestreamUrl clone();
}
