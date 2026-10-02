package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.UserContext;
import com.flowmind.dto.publish.PublishVO;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowApiKey;
import com.flowmind.mapper.WorkflowApiKeyMapper;
import com.flowmind.mapper.WorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * 工作流 API 发布（阶梯 8）：生成/吊销 API Key。
 * key 用 32 字节 SecureRandom hex，仅在发布响应中完整展示一次，之后 GET 只回占位模板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishService {

    /** runUrl 的 {apiKey} 占位符（GET 查询时不重暴露真实 key） */
    public static final String KEY_PLACEHOLDER = "{apiKey}";

    private final WorkflowApiKeyMapper apiKeyMapper;
    private final WorkflowMapper workflowMapper;

    /** 发布（幂等）：已发布返回现有信息但不重暴露 key；未发布生成新 key 一次性返回。 */
    public PublishVO publish(Long workflowId) {
        loadOwnedWorkflow(workflowId);
        WorkflowApiKey existing = selectByWorkflow(workflowId);
        if (existing != null) {
            PublishVO vo = new PublishVO();
            vo.setPublished(true);
            vo.setCreatedAt(existing.getCreatedAt());
            return vo;
        }
        WorkflowApiKey key = new WorkflowApiKey();
        key.setWorkflowId(workflowId);
        // 明文存储 MVP：生产标准做法是存 SHA-256 哈希并按哈希索引（防拖库泄露后 key 被复用）
        key.setApiKey(generateKey());
        apiKeyMapper.insert(key);

        PublishVO vo = new PublishVO();
        vo.setPublished(true);
        vo.setApiKey(key.getApiKey());
        vo.setCreatedAt(key.getCreatedAt());
        return vo;
    }

    public PublishVO get(Long workflowId) {
        loadOwnedWorkflow(workflowId);
        WorkflowApiKey existing = selectByWorkflow(workflowId);
        PublishVO vo = new PublishVO();
        if (existing == null) {
            vo.setPublished(false);
            return vo;
        }
        vo.setPublished(true);
        vo.setCreatedAt(existing.getCreatedAt());
        return vo;
    }

    /** 吊销（幂等）。 */
    public void revoke(Long workflowId) {
        loadOwnedWorkflow(workflowId);
        apiKeyMapper.delete(new LambdaQueryWrapper<WorkflowApiKey>()
                .eq(WorkflowApiKey::getWorkflowId, workflowId));
        log.info("API 发布已吊销: workflowId={}", workflowId);
    }

    /** public 调用入口的 key 查询（无 → INVALID_API_KEY）。 */
    public WorkflowApiKey findByKey(String apiKey) {
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<WorkflowApiKey>()
                .eq(WorkflowApiKey::getApiKey, apiKey));
    }

    public static String generateKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Workflow loadOwnedWorkflow(Long workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
        }
        if (!workflow.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权发布他人的工作流");
        }
        return workflow;
    }

    private WorkflowApiKey selectByWorkflow(Long workflowId) {
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<WorkflowApiKey>()
                .eq(WorkflowApiKey::getWorkflowId, workflowId));
    }
}
