package com.example.ratelimiter.domain;

public record Context(
        String tenant, String user, String apiKey, String ip, String tier, String endpoint, long cost) {
    public String identity(Policy.Dimension d) {
        return switch (d) {
            case GLOBAL -> "global";
            case TENANT -> tenant;
            case USER -> tenant + ":" + user;
            case API_KEY -> tenant + ":" + apiKey;
            case IP -> ip;
            case ENDPOINT -> endpoint;
        };
    }
}
