package com.flowmind.dto.publish;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布信息：POST 发布时 apiKey+runUrl 一次性完整返回；
 * GET 查询时 apiKey 为 null、runUrl 为 {apiKey} 占位模板（不重暴露 key）。
 */
@Data
public class PublishVO {

    private boolean published;
    private String apiKey;
    private String runUrl;
    private LocalDateTime createdAt;
}
