package com.flowmind.engine;

/** 引擎内部错误（未注册执行器、非法节点类型等）。 */
public class EngineException extends RuntimeException {

    public EngineException(String message) {
        super(message);
    }
}
