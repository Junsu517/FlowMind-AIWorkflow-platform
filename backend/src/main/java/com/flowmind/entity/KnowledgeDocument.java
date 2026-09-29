package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** chunk 本体存 Chroma，MySQL 只存文档元数据与 chunk_count */
@Data
@TableName("knowledge_documents")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String filename;

    private Integer chunkCount;

    private Integer fileSize;

    private LocalDateTime createdAt;
}
