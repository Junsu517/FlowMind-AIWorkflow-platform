package com.flowmind.dto.workflow;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** PUT /workflows/{id} 可只传要改的字段，null 表示不更新 */
@Data
public class WorkflowUpdateRequest {

    @Size(max = 100, message = "名称最长 100 字符")
    private String name;

    @Size(max = 500, message = "描述最长 500 字符")
    private String description;
}
