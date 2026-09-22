package com.flowmind.engine;

/** 图存在环，无法执行。 */
public class CycleException extends RuntimeException {

    public CycleException(String message) {
        super(message);
    }
}
