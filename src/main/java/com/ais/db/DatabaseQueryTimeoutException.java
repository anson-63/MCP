package com.ais.db;

public class DatabaseQueryTimeoutException extends RuntimeException {
    public DatabaseQueryTimeoutException(String message,Throwable cause) {
        super(message, cause);
    }
}