package com.flowmind.service;

import lombok.RequiredArgsConstructor;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 定时触发 Job：薄壳，只取 workflowId 转发 fireScheduled（便于单测直调）。
 * 触发逻辑全在 ScheduleService：UserContext 注入归属用户 → submit（triggerType=scheduled）。
 */
@Component
@DisallowConcurrentExecution
@RequiredArgsConstructor
public class ScheduleJob implements Job {

    @Autowired
    private ScheduleService scheduleService;

    @Override
    public void execute(JobExecutionContext context) {
        Long workflowId = context.getMergedJobDataMap().getLong("workflowId");
        scheduleService.fireScheduled(workflowId);
    }
}
