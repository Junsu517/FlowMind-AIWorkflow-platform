package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.entity.Execution;
import com.flowmind.mapper.ExecutionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 孤儿 running 执行恢复：2 条 running 翻 failed（error_message/finishedAt 正确），success 行不动。
 * 注：@SpringBootTest 上下文启动时 listener 已跑过一次（真实路径），此处直接调方法验证行为。
 */
@SpringBootTest
class ExecutionRecoveryServiceTest {

    @Autowired
    private ExecutionRecoveryService recoveryService;

    @Autowired
    private ExecutionMapper executionMapper;

    private Execution insert(String status) {
        Execution e = new Execution();
        e.setUserId(999999L);
        e.setWorkflowId(999999L);
        e.setStatus(status);
        e.setStartedAt(LocalDateTime.now());
        return e;
    }

    @AfterEach
    void tearDown() {
        executionMapper.delete(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getUserId, 999999L));
    }

    @Test
    void recoversOrphanedRunningLeavesFinishedUntouched() {
        Execution running1 = insert("running");
        Execution running2 = insert("running");
        Execution success = insert("success");
        executionMapper.insert(running1);
        executionMapper.insert(running2);
        executionMapper.insert(success);

        recoveryService.recoverOrphanedExecutions();

        Execution after1 = executionMapper.selectById(running1.getId());
        Execution after2 = executionMapper.selectById(running2.getId());
        assertEquals("failed", after1.getStatus());
        assertEquals(ExecutionRecoveryService.RECOVERY_ERROR_MESSAGE, after1.getErrorMessage());
        assertNotNull(after1.getFinishedAt(), "恢复应补 finishedAt");
        assertEquals("failed", after2.getStatus());
        assertEquals(ExecutionRecoveryService.RECOVERY_ERROR_MESSAGE, after2.getErrorMessage());

        assertEquals("success", executionMapper.selectById(success.getId()).getStatus(),
                "已结束的执行不受影响");

        // 幂等：再跑一次无 running 可翻
        recoveryService.recoverOrphanedExecutions();
        List<Execution> stillRunning = executionMapper.selectList(
                new LambdaQueryWrapper<Execution>().eq(Execution::getUserId, 999999L)
                        .eq(Execution::getStatus, "running"));
        assertEquals(0, stillRunning.size());
    }
}
