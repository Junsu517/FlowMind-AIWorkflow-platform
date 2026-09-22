package com.flowmind.engine;

/** 模板渲染失败：引用了不存在的上下文 key。 */
public class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }
}
