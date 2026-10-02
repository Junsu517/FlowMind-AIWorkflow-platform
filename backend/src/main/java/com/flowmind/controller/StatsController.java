package com.flowmind.controller;

import com.flowmind.dto.stats.DashboardVO;
import com.flowmind.dto.stats.UsageVO;
import com.flowmind.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/usage")
    public UsageVO usage() {
        return statsService.usage();
    }

    @GetMapping("/dashboard")
    public DashboardVO dashboard() {
        return statsService.dashboard();
    }
}
