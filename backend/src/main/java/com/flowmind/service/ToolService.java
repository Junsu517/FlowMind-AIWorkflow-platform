package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.dto.workflow.ToolVO;
import com.flowmind.entity.Tool;
import com.flowmind.mapper.ToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ToolService {

    private final ToolMapper toolMapper;
    private final ObjectMapper objectMapper;

    public List<ToolVO> listEnabled() {
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getEnabled, 1)
                        .orderByAsc(Tool::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private ToolVO toVO(Tool tool) {
        ToolVO vo = new ToolVO();
        vo.setId(tool.getId());
        vo.setName(tool.getName());
        vo.setDisplayName(tool.getDisplayName());
        vo.setDescription(tool.getDescription());
        vo.setMethod(tool.getMethod());
        vo.setParamsSchema(readTree(tool.getParamsSchema()));
        return vo;
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工具 paramsSchema 解析失败: " + toolSafeName(json));
        }
    }

    private String toolSafeName(String json) {
        return json.length() > 50 ? json.substring(0, 50) : json;
    }
}
