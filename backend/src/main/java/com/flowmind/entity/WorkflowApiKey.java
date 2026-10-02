package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流 API 发布密钥（一工作流一把，uk_workflow/uk_key） */
@Data
@TableName("workflow_api_keys")
public class WorkflowApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    /** 32 字节随机 hex。MVP 存明文；生产标准做法是存 SHA-256 哈希并按哈希索引查找（防拖库泄露） */
    private String apiKey;

    private LocalDateTime createdAt;
}
