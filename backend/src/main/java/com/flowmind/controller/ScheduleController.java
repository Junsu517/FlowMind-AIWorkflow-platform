package com.flowmind.controller;

import com.flowmind.dto.schedule.ScheduleRequest;
import com.flowmind.dto.schedule.ScheduleVO;
import com.flowmind.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows/{id}/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /** 未配置返回 {"configured":false}（200），避免与 404 工作流不存在混淆。 */
    @GetMapping
    public ScheduleVO get(@PathVariable Long id) {
        return scheduleService.get(id);
    }

    @PutMapping
    public ScheduleVO upsert(@PathVariable Long id, @Valid @RequestBody ScheduleRequest req) {
        return scheduleService.upsert(id, req);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
