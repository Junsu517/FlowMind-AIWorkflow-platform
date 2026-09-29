package com.flowmind.dto.knowledge;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocumentVO {

    private Long id;
    private Long kbId;
    private String filename;
    private Integer chunkCount;
    private Integer fileSize;
    private LocalDateTime createdAt;
}
