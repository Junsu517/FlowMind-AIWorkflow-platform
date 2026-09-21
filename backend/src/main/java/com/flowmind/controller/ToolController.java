package com.flowmind.controller;

import com.flowmind.dto.workflow.ToolVO;
import com.flowmind.service.ToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

    @GetMapping
    public Map<String, Object> list() {
        List<ToolVO> items = toolService.listEnabled();
        return Map.of("items", items);
    }
}
