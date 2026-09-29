package com.flowmind.dto.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** 列表场景带 documentCount；创建场景实时统计 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnowledgeBaseVO {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private Long documentCount;
    private LocalDateTime createdAt;
}
