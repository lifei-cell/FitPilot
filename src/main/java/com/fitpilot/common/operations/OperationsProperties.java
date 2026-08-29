package com.fitpilot.common.operations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitpilot.operations")
public class OperationsProperties {
    private String token = "";
    public String getToken(){return token;}
    public void setToken(String value){token=value;}
}
