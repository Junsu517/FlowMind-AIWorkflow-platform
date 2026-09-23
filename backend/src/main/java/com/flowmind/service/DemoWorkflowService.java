package com.flowmind.service;

import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 新用户注册即送"天气助手（示例）"工作流（用户实测反馈第 8 条）：
 * 输入城市 → 查询实时天气 → AI 穿衣建议，注册后直接可跑可学。
 * 节点坐标与前端画布模板一致（x: 85/418/752, y: 112）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoWorkflowService {

    static final String DEMO_NAME = "天气助手（示例）";
    static final String DEMO_DESCRIPTION = "官方示例：输入城市 → 查询实时天气 → AI 穿衣建议。点开编辑器可自由修改";

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;

    /** 直接创建（允许重名，列表按名字展示无碍）；调用方负责 try/catch。 */
    public void createDemoWorkflowFor(Long userId) {
        Workflow workflow = new Workflow();
        workflow.setUserId(userId);
        workflow.setName(DEMO_NAME);
        workflow.setDescription(DEMO_DESCRIPTION);
        workflowMapper.insert(workflow);
        Long workflowId = workflow.getId();

        insertNode(workflowId, "input_1", "input", "输入：城市",
                "{\"fields\":[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\",\"default\":\"南昌\"}]}",
                85D, 112D);
        insertNode(workflowId, "http_1", "http", "查询天气",
                "{\"tool_id\":1,\"params\":{\"city\":\"{{input.city}}\"},\"output_key\":\"data\"}",
                418D, 112D);
        insertNode(workflowId, "llm_1", "llm", "AI 穿衣建议",
                "{\"model\":\"deepseek-flash\",\"prompt\":\"城市 {{input.city}} 的天气数据：{{http_1.data}}。请给出一条简洁的穿衣建议。\",\"temperature\":0.7,\"output_key\":\"text\"}",
                752D, 112D);

        insertEdge(workflowId, "input_1", "http_1");
        insertEdge(workflowId, "http_1", "llm_1");
        log.info("已为新用户创建示例工作流: userId={}, workflowId={}", userId, workflowId);
    }

    private void insertNode(Long workflowId, String nodeKey, String type, String name,
                            String config, Double x, Double y) {
        WorkflowNode node = new WorkflowNode();
        node.setWorkflowId(workflowId);
        node.setNodeKey(nodeKey);
        node.setType(type);
        node.setName(name);
        node.setConfig(config);
        node.setPositionX(x);
        node.setPositionY(y);
        nodeMapper.insert(node);
    }

    private void insertEdge(Long workflowId, String source, String target) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflowId(workflowId);
        edge.setSourceNodeKey(source);
        edge.setTargetNodeKey(target);
        edgeMapper.insert(edge);
    }
}
