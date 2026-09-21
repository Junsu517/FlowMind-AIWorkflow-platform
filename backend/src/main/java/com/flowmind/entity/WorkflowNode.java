package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_nodes")
public class WorkflowNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private String nodeKey;

    private String type;

    private String name;

    /** JSON 字符串，见 dto.NodeDTO.config */
    private String config;

    private Double positionX;

    private Double positionY;

    private LocalDateTime createdAt;
}
