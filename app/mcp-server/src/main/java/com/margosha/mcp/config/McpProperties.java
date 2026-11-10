package com.margosha.mcp.config;

import java.time.Duration;

import lombok.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("margosha.mcp")
public record McpProperties(@NonNull String coordinatorUrl, @NonNull Duration timeout) { }
