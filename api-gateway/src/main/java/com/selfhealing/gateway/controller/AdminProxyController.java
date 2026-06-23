package com.selfhealing.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/**
 * Fallback proxy controller for local development.
 * 
 * When running microservices locally in an IDE, the frontend only proxies 
 * "/api" requests to port 8080 (API Gateway). This controller intercepts 
 * administrative requests for AI Analysis and Rules and proxies them to the
 * appropriate microservices (ports 8082 and 8084).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminProxyController {

    private final RestTemplate restTemplate;

    @Value("${services.ai-analysis:http://localhost:8082}")
    private String aiAnalysisUrl;

    @Value("${services.rule-engine:http://localhost:8084}")
    private String ruleEngineUrl;

    @RequestMapping(value = "/api/analysis/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<?> proxyAnalysis(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String url = aiAnalysisUrl + path + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        log.debug("Proxying admin request to AI Analysis Service: {} {}", request.getMethod(), url);
        return forwardRequest(request, url, body);
    }

    @RequestMapping(value = "/api/rules/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<?> proxyRules(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String url = ruleEngineUrl + path + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        log.debug("Proxying admin request to Rule Engine: {} {}", request.getMethod(), url);
        return forwardRequest(request, url, body);
    }

    private ResponseEntity<?> forwardRequest(HttpServletRequest request, String url, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!headerName.equalsIgnoreCase("host") &&
                !headerName.equalsIgnoreCase("connection") &&
                !headerName.equalsIgnoreCase("transfer-encoding") &&
                !headerName.equalsIgnoreCase("content-length")) {
                headers.put(headerName, Collections.list(request.getHeaders(headerName)));
            }
        });

        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.valueOf(request.getMethod()), entity, byte[].class);
            return sanitizeResponse(response);
        } catch (HttpStatusCodeException ex) {
            return sanitizeErrorResponse(ex);
        } catch (Exception ex) {
            log.error("Failed to proxy admin request to {}: {}", url, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }

    private ResponseEntity<byte[]> sanitizeResponse(ResponseEntity<byte[]> response) {
        HttpHeaders responseHeaders = new HttpHeaders();
        response.getHeaders().forEach((headerName, headerValues) -> {
            if (!headerName.equalsIgnoreCase("transfer-encoding") &&
                !headerName.equalsIgnoreCase("content-length") &&
                !headerName.equalsIgnoreCase("content-encoding") &&
                !headerName.equalsIgnoreCase("connection") &&
                !headerName.equalsIgnoreCase("keep-alive") &&
                !headerName.equalsIgnoreCase("server")) {
                responseHeaders.addAll(headerName, headerValues);
            }
        });
        return ResponseEntity.status(response.getStatusCode())
                .headers(responseHeaders)
                .body(response.getBody());
    }

    private ResponseEntity<?> sanitizeErrorResponse(HttpStatusCodeException ex) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (ex.getResponseHeaders() != null) {
            ex.getResponseHeaders().forEach((headerName, headerValues) -> {
                if (!headerName.equalsIgnoreCase("transfer-encoding") &&
                    !headerName.equalsIgnoreCase("content-length") &&
                    !headerName.equalsIgnoreCase("content-encoding") &&
                    !headerName.equalsIgnoreCase("connection") &&
                    !headerName.equalsIgnoreCase("keep-alive") &&
                    !headerName.equalsIgnoreCase("server")) {
                    responseHeaders.addAll(headerName, headerValues);
                }
            });
        }
        return ResponseEntity.status(ex.getStatusCode())
                .headers(responseHeaders)
                .body(ex.getResponseBodyAsByteArray());
    }
}
