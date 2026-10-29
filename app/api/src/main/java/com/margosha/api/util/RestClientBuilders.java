package com.margosha.api.util;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class RestClientBuilders {

    private RestClientBuilders() { }

    /**
     * A builder whose calls fail with {@code ResourceAccessException} when the server does not accept the
     * connection, or does not answer, within {@code timeout}.
     */
    public static RestClient.Builder withTimeout(Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
