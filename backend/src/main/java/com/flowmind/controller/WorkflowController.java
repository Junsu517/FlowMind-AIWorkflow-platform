package com.flowmind.controller;

import com.flowmind.dto.workflow.GraphSaveRequest;
import com.flowmind.dto.workflow.GraphVO;
import com.flowmind.dto.workflow.PageVO;
import com.flowmind.dto.workflow.WorkflowCreateRequest;
import com.flowmind.dto.workflow.WorkflowUpdateRequest;
import com.flowmind.dto.workflow.WorkflowVO;
import com.flowmind.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    public PageVO<WorkflowVO> list(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int size) {
        return workflowService.list(page, size);
    }

    @PostMapping
    public ResponseEntity<WorkflowVO> create(@Valid @RequestBody WorkflowCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.create(req));
    }

    @GetMapping("/{id}")
    public WorkflowVO get(@PathVariable Long id) {
        return workflowService.get(id);
    }

    @PutMapping("/{id}")
    public WorkflowVO update(@PathVariable Long id, @Valid @RequestBody WorkflowUpdateRequest req) {
        return workflowService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/graph")
    public Map<String, Boolean> saveGraph(@PathVariable Long id,
                                          @Valid @RequestBody GraphSaveRequest req) {
        workflowService.saveGraph(id, req);
        return Map.of("saved", true);
    }

    @GetMapping("/{id}/graph")
    public GraphVO getGraph(@PathVariable Long id) {
        return workflowService.getGraph(id);
    }
}
