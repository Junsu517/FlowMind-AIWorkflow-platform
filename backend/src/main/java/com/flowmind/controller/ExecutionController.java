package com.flowmind.controller;

import com.flowmind.dto.execution.ExecuteRequest;
import com.flowmind.dto.execution.ExecutionVO;
import com.flowmind.dto.workflow.PageVO;
import com.flowmind.service.ExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionService executionService;

    @PostMapping("/api/workflows/{id}/execute")
    public ResponseEntity<Map<String, Long>> execute(@PathVariable Long id,
                                                     @Valid @RequestBody ExecuteRequest req) {
        Long executionId = executionService.submit(id, req.getInput());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("executionId", executionId));
    }

    @GetMapping("/api/executions/{id}")
    public ExecutionVO get(@PathVariable Long id) {
        return executionService.get(id);
    }

    @GetMapping("/api/executions/{id}/logs")
    public Map<String, Object> logs(@PathVariable Long id) {
        return executionService.logs(id);
    }

    @GetMapping("/api/executions")
    public PageVO<ExecutionVO> list(@RequestParam(required = false) Long workflowId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return executionService.list(workflowId, page, size);
    }
}
