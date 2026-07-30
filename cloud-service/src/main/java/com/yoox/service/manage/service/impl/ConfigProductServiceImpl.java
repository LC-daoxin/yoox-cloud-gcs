package com.yoox.service.manage.service.impl;

import com.yoox.service.manage.service.IRequestsConfigService;
import com.yoox.service.manage.model.common.AppLicenseProperties;
import com.yoox.service.manage.model.common.NtpServerProperties;
import com.yoox.service.manage.model.dto.ProductConfigDTO;
import org.springframework.stereotype.Service;

/**
 * @author sean
 * @version 1.3
 * @date 2022/11/10
 */
@Service
public class ConfigProductServiceImpl implements IRequestsConfigService {

    @Override
    public Object getConfig() {
        return new ProductConfigDTO(NtpServerProperties.host, AppLicenseProperties.id, AppLicenseProperties.key, AppLicenseProperties.license);
    }
}
