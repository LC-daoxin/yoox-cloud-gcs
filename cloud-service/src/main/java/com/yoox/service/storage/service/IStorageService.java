package com.yoox.service.storage.service;


import com.yoox.great.mqtt.model.storage.StsCredentialsResponse;

public interface IStorageService {

    StsCredentialsResponse getSTSCredentials();

}
