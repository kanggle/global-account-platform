package com.example.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient jwksWebClient(GatewayProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getJwt().getJwksUrl())
                .build();
    }
}
