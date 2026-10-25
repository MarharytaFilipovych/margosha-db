package com.margosha.api.exception;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final int status;
    private final ApiError error;

    public ApiException(int status, ApiError error, Throwable cause) {
        super(buildMessage(status, error), cause);
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public ApiError error() {
        return error;
    }

    public ErrorCode code() {
        return error != null ? error.code() : null;
    }

    public boolean isValidationError() {
        return status == HttpStatus.BAD_REQUEST.value();
    }

    public boolean isNotFound() {
        return status == HttpStatus.NOT_FOUND.value();
    }

    public boolean isConflict() {
        return status == HttpStatus.CONFLICT.value();
    }

    public boolean isServerError() {
        return status >= 500 && status < 600;
    }

    public boolean isUnavailable() {
        return status == HttpStatus.SERVICE_UNAVAILABLE.value();
    }

    private static String buildMessage(int status, ApiError error) {
        return error == null
                ? "HTTP " + status
                : "HTTP " + status + " " + error.code() + ": " + error.message();
    }
}
