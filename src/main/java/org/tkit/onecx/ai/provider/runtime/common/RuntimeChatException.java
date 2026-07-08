package org.tkit.onecx.ai.provider.runtime.common;

import jakarta.ws.rs.core.Response;

public class RuntimeChatException extends RuntimeException {

    private final String errorCode;
    private final String errorType;
    private final String detail;
    private final int statusCode;

    public RuntimeChatException(String errorCode, String errorType, String detail, Response.Status status) {
        this(errorCode, errorType, detail,
                status != null ? status.getStatusCode() : Response.Status.BAD_REQUEST.getStatusCode());
    }

    public RuntimeChatException(String errorCode, String errorType, String detail, int statusCode) {
        super(detail);
        this.errorCode = errorCode;
        this.errorType = errorType;
        this.detail = detail;
        this.statusCode = statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getDetail() {
        return detail;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
