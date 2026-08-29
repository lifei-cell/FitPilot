package com.fitpilot.llm.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitpilot.llm")
public class LlmProperties {
    private boolean enabled;
    private int connectTimeoutMs = 3000;
    private int requestTimeoutSeconds = 15;
    private int maxRetries = 2;
    private int circuitFailureThreshold = 5;
    private int circuitOpenSeconds = 30;
    private String promptVersion = "v5.1";
    private int maxContextChars = 12000;
    private final Endpoint primary = new Endpoint("primary");
    private final Endpoint fallback = new Endpoint("fallback");

    @PostConstruct
    void validate() {
        if (connectTimeoutMs < 100 || requestTimeoutSeconds < 1 || maxRetries < 0 || maxRetries > 5)
            throw new IllegalStateException("invalid LLM timeout or retry configuration");
        if (circuitFailureThreshold < 1 || circuitOpenSeconds < 1 || maxContextChars < 1000)
            throw new IllegalStateException("invalid LLM circuit or context configuration");
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) { maxRetries = value; }
    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int value) { circuitFailureThreshold = value; }
    public int getCircuitOpenSeconds() { return circuitOpenSeconds; }
    public void setCircuitOpenSeconds(int value) { circuitOpenSeconds = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public int getMaxContextChars() { return maxContextChars; }
    public void setMaxContextChars(int value) { maxContextChars = value; }
    public Endpoint getPrimary() { return primary; }
    public Endpoint getFallback() { return fallback; }

    public static class Endpoint {
        private String name;
        private String url = "";
        private String apiKey = "";
        private String smallModel = "";
        private String mediumModel = "";
        private String strongModel = "";
        private double inputCostPerMillion;
        private double outputCostPerMillion;
        public Endpoint() { this(""); }
        Endpoint(String name) { this.name = name; }
        public boolean configured() { return !url.isBlank() && !smallModel.isBlank(); }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getSmallModel() { return smallModel; }
        public void setSmallModel(String value) { smallModel = value; }
        public String getMediumModel() { return mediumModel.isBlank() ? smallModel : mediumModel; }
        public void setMediumModel(String value) { mediumModel = value; }
        public String getStrongModel() { return strongModel.isBlank() ? getMediumModel() : strongModel; }
        public void setStrongModel(String value) { strongModel = value; }
        public double getInputCostPerMillion() { return inputCostPerMillion; }
        public void setInputCostPerMillion(double value) { inputCostPerMillion = value; }
        public double getOutputCostPerMillion() { return outputCostPerMillion; }
        public void setOutputCostPerMillion(double value) { outputCostPerMillion = value; }
    }
}
