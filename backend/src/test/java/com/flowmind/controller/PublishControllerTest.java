package com.flowmind.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** runUrl 拼接：本地直连（空前缀）与生产 /fm 前缀两种形态。 */
class PublishControllerTest {

    @Test
    void emptyPrefixKeepsLocalBehavior() {
        assertEquals("http://127.0.0.1:8080/api/public/run/abc123",
                PublishController.buildRunUrl("http://127.0.0.1:8080", "", "abc123"));
        assertEquals("http://127.0.0.1:8080/api/public/run/abc123",
                PublishController.buildRunUrl("http://127.0.0.1:8080", null, "abc123"));
    }

    @Test
    void fmPrefixProducesNginxPath() {
        assertEquals("http://43.108.111.175/fm/api/public/run/abc123",
                PublishController.buildRunUrl("http://43.108.111.175", "/fm", "abc123"));
        assertEquals("http://43.108.111.175/fm/api/public/run/{apiKey}",
                PublishController.buildRunUrl("http://43.108.111.175", "/fm", "{apiKey}"));
    }

    @Test
    void trailingSlashNormalized() {
        assertEquals("http://x/fm/api/public/run/k",
                PublishController.buildRunUrl("http://x", "/fm/", "k"));
    }

    @Test
    void barePrefixGetsLeadingSlash() {
        // "fm" 与 "/fm" 等价（容错：本地 MSYS 环境传值可避开斜杠转换）
        assertEquals("http://x/fm/api/public/run/k",
                PublishController.buildRunUrl("http://x", "fm", "k"));
    }
}
