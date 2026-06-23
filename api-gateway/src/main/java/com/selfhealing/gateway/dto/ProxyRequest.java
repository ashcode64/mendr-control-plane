package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProxyRequest {
    private String sourceService;
    private String targetService;
    private String endpoint;
    private String method;
    private Map<String, Object> payload;
    private Map<String, String> headers;
}
