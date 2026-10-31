package com.margosha.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(ErrorCode code, String message, Long version) {

    public ApiError(ErrorCode code, String message) {
        this(code, message, null);
    }
}
