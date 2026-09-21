package com.flowmind.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EdgeDTO {

    @NotBlank(message = "sourceNodeKey 不能为空")
    @Size(max = 50)
    private String sourceNodeKey;

    @NotBlank(message = "targetNodeKey 不能为空")
    @Size(max = 50)
    private String targetNodeKey;

    /** 条件分支名，普通边为 null */
    private String sourceHandle;
}
