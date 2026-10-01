package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.UserContext;
import com.flowmind.dto.schedule.ScheduleRequest;
import com.flowmind.dto.schedule.ScheduleVO;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowSchedule;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.JobDetailImpl;
import org.quartz.CronExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 定时计划（阶梯 7）：DB 是真相源，Quartz 内存模式只负责到点触发；
 * 触发即走 submit 链路（锁→queued→MQ），队列/重试/幂等全部免费继承。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    static final String JOB_GROUP = "flowmind-schedules";

    private final Scheduler scheduler;
    private final WorkflowScheduleMapper scheduleMapper;
    private final WorkflowMapper workflowMapper;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;

    // ---------------- 对外接口 ----------------

    public ScheduleVO get(Long workflowId) {
        loadOwnedWorkflow(workflowId);
        WorkflowSchedule schedule = selectByWorkflow(workflowId);
        return toVO(schedule);
    }

    public ScheduleVO upsert(Long workflowId, ScheduleRequest req) {
        loadOwnedWorkflow(workflowId);
        if (!CronExpression.isValidExpression(req.getCron())) {
            throw new BusinessException(ErrorCode.SCHEDULE_INVALID,
                    "cron 表达式非法: " + req.getCron());
        }
        boolean enabled = req.getEnabled() == null || req.getEnabled();

        WorkflowSchedule schedule = selectByWorkflow(workflowId);
        if (schedule == null) {
            schedule = new WorkflowSchedule();
            schedule.setWorkflowId(workflowId);
        }
        schedule.setCron(req.getCron());
        schedule.setEnabled(enabled ? 1 : 0);
        schedule.setInput(writeJson(req.getInput()));
        if (schedule.getId() == null) {
            scheduleMapper.insert(schedule);
        } else {
            scheduleMapper.updateById(schedule);
        }

        if (enabled) {
            registerJob(schedule);
        } else {
            unregisterJob(workflowId);
        }
        log.info("定时计划已保存: workflowId={}, cron={}, enabled={}", workflowId, req.getCron(), enabled);
        return toVO(selectByWorkflow(workflowId));
    }

    public void delete(Long workflowId) {
        loadOwnedWorkflow(workflowId);
        scheduleMapper.delete(new LambdaQueryWrapper<WorkflowSchedule>()
                .eq(WorkflowSchedule::getWorkflowId, workflowId));
        unregisterJob(workflowId);
        log.info("定时计划已删除: workflowId={}", workflowId);
    }

    // ---------------- Quartz 动态注册 ----------------

    static JobKey jobKey(Long workflowId) {
        return JobKey.jobKey("sched-" + workflowId, JOB_GROUP);
    }

    void registerJob(WorkflowSchedule schedule) {
        try {
            JobDetailImpl job = new JobDetailImpl();
            job.setName(jobKey(schedule.getWorkflowId()).getName());
            job.setGroup(JOB_GROUP);
            job.setJobClass(ScheduleJob.class);
            job.getJobDataMap().put("workflowId", schedule.getWorkflowId());

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger-" + schedule.getWorkflowId(), JOB_GROUP)
                    .forJob(job)
                    .withSchedule(CronScheduleBuilder.cronSchedule(schedule.getCron())
                            // misfire 选 FIRE_NOW（错过立即补一次）：触发即入 MQ，锁+幂等兜底，
                            // 补触发撞上手动执行只会被 409 挡掉，比静默跳过一个周期对用户更友好
                            .withMisfireHandlingInstructionFireAndProceed())
                    .build();
            // 更新即替换：先注销旧注册（不存在时 deleteJob 返回 false，无害）
            scheduler.deleteJob(jobKey(schedule.getWorkflowId()));
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException e) {
            log.error("定时任务注册失败: workflowId={}", schedule.getWorkflowId(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "定时任务注册失败: " + e.getMessage());
        }
    }

    void unregisterJob(Long workflowId) {
        try {
            scheduler.deleteJob(jobKey(workflowId));
        } catch (SchedulerException e) {
            log.warn("定时任务注销失败（重启恢复会重新对齐）: workflowId={}, {}", workflowId, e.getMessage());
        }
    }

    /** 启动恢复：内存模式重启丢注册，从 DB 重载全部启用的计划。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverSchedules() {
        List<WorkflowSchedule> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<WorkflowSchedule>().eq(WorkflowSchedule::getEnabled, 1));
        if (schedules.isEmpty()) {
            log.info("定时计划恢复：无启用计划");
            return;
        }
        int recovered = 0;
        for (WorkflowSchedule schedule : schedules) {
            try {
                registerJob(schedule);
                recovered++;
            } catch (Exception e) {
                log.error("计划恢复失败（跳过）: workflowId={}", schedule.getWorkflowId(), e);
            }
        }
        log.info("定时计划恢复：{} 条已重新注册", recovered);
    }

    // ---------------- 触发（ScheduleJob 入口，单测直调） ----------------

    public void fireScheduled(Long workflowId) {
        WorkflowSchedule schedule = selectByWorkflow(workflowId);
        if (schedule == null || schedule.getEnabled() == null || schedule.getEnabled() != 1) {
            log.warn("定时触发跳过（计划不存在或已停用）: workflowId={}", workflowId);
            return;
        }
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            log.warn("定时触发跳过（工作流已删除）: workflowId={}", workflowId);
            return;
        }
        UserContext.set(workflow.getUserId(), "user");
        try {
            Map<String, Object> input = readInputMap(schedule.getInput());
            executionService.submit(workflowId, input, "scheduled");
            schedule.setLastRunAt(LocalDateTime.now());
            scheduleMapper.updateById(schedule);
        } catch (BusinessException e) {
            // 409/限流/入参校验属业务拒绝：记录即可，不更新 last_run_at
            log.warn("定时触发被拒: workflowId={}, code={}, {}", workflowId,
                    e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("定时触发异常: workflowId={}", workflowId, e);
        } finally {
            UserContext.clear();
        }
    }

    // ---------------- 私有 ----------------

    private Workflow loadOwnedWorkflow(Long workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
        }
        if (!workflow.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人的工作流");
        }
        return workflow;
    }

    private WorkflowSchedule selectByWorkflow(Long workflowId) {
        return scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowSchedule>()
                .eq(WorkflowSchedule::getWorkflowId, workflowId));
    }

    private ScheduleVO toVO(WorkflowSchedule schedule) {
        ScheduleVO vo = new ScheduleVO();
        if (schedule == null) {
            vo.setConfigured(false);
            return vo;
        }
        vo.setConfigured(true);
        vo.setCron(schedule.getCron());
        vo.setEnabled(schedule.getEnabled() != null && schedule.getEnabled() == 1);
        vo.setInput(readInputJson(schedule.getInput()));
        vo.setLastRunAt(schedule.getLastRunAt());
        try {
            Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey(
                    "trigger-" + schedule.getWorkflowId(), JOB_GROUP));
            if (trigger != null && trigger.getNextFireTime() != null) {
                vo.setNextFireTime(toLocalDateTime(trigger.getNextFireTime()));
            }
        } catch (SchedulerException e) {
            log.debug("读取 nextFireTime 失败: {}", e.getMessage());
        }
        return vo;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private Map<String, Object> readInputMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private JsonNode readInputJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
