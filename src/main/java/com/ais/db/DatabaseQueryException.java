package com.ais.db;

public class DatabaseQueryException extends RuntimeException {
    public DatabaseQueryException(String message,Throwable cause) {
        super(message, cause);
    }
}