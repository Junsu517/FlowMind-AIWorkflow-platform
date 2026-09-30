package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flowmind.entity.Execution;
import com.flowmind.mapper.ExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用启动恢复：服务重启意味着 JVM 内一切在途执行任务已死，
 * 残留的 running 行是孤儿（永远 running、所在工作流永久 409）。
 * 启动完成后统一翻成 failed，让用户可以重新执行。
 * 阶梯 6 语义：**queued 行不翻**——MQ 模式下任务在队列里活着，消费者恢复后继续处理；
 * thread 模式下 queued 是极端残留（提交后线程池未跑即崩），数量极少，同样留待下次消费窗口。
 * 背景：submit() 先落 running 行再交线程池，JVM 在两步之间或执行中死亡即产生孤儿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRecoveryService {

    static final String RECOVERY_ERROR_MESSAGE = "服务重启，执行中断";

    private final ExecutionMapper executionMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedExecutions() {
        List<Execution> orphans = executionMapper.selectList(
                new LambdaQueryWrapper<Execution>().eq(Execution::getStatus, "running"));
        if (orphans.isEmpty()) {
            log.info("启动恢复：无孤儿 running 执行");
            return;
        }
        int updated = executionMapper.update(null, new LambdaUpdateWrapper<Execution>()
                .eq(Execution::getStatus, "running")
                .set(Execution::getStatus, "failed")
                .set(Execution::getErrorMessage, RECOVERY_ERROR_MESSAGE)
                .set(Execution::getFinishedAt, LocalDateTime.now()));
        log.warn("启动恢复：{} 条孤儿 running 执行已翻为 failed（重启前的在途任务随 JVM 丢失）", updated);
        orphans.forEach(o -> log.warn("启动恢复: executionId={} workflowId={} startedAt={}",
                o.getId(), o.getWorkflowId(), o.getStartedAt()));
    }
}
