package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工具表，预置数据见 schema.sql；接口暴露留待后续周 */
@Data
@TableName("tools")
public class Tool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String displayName;

    private String description;

    private String method;

    private String urlTemplate;

    private String headers;

    private String bodyTemplate;

    private String paramsSchema;

    private Integer enabled;

    private LocalDateTime createdAt;
}
