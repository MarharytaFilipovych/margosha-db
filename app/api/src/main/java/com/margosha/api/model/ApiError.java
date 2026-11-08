package com.margosha.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(ErrorCode code, String message,
                       Long logVersion, RecordVersion recordVersion) {

    public ApiError(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ApiError(ErrorCode code, String message, Long logVersion) {
        this(code, message, logVersion, null);
    }
}
