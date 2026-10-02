package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.UserContext;
import com.flowmind.dto.workflow.EdgeDTO;
import com.flowmind.dto.workflow.GraphSaveRequest;
import com.flowmind.dto.workflow.GraphVO;
import com.flowmind.dto.workflow.NodeDTO;
import com.flowmind.dto.workflow.PageVO;
import com.flowmind.dto.workflow.WorkflowCreateRequest;
import com.flowmind.dto.workflow.WorkflowUpdateRequest;
import com.flowmind.dto.workflow.WorkflowVO;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowApiKey;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.WorkflowApiKeyMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowApiKeyMapper apiKeyMapper;
    private final GraphValidator graphValidator;
    private final ObjectMapper objectMapper;

    public PageVO<WorkflowVO> list(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size < 1 ? 10 : Math.min(size, 50);
        Long userId = UserContext.getUserId();
        var result = workflowMapper.selectPageWithNodeCount(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize), userId);
        return new PageVO<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public WorkflowVO create(WorkflowCreateRequest req) {
        Workflow workflow = new Workflow();
        workflow.setUserId(UserContext.getUserId());
        workflow.setName(req.getName());
        workflow.setDescription(req.getDescription() == null ? "" : req.getDescription());
        workflowMapper.insert(workflow);
        return toVO(workflowMapper.selectById(workflow.getId()));
    }

    public WorkflowVO get(Long id) {
        return toVO(loadOwned(id));
    }

    public WorkflowVO update(Long id, WorkflowUpdateRequest req) {
        loadOwned(id);
        Workflow patch = new Workflow();
        patch.setId(id);
        if (req.getName() != null) {
            if (req.getName().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工作流名称不能为空");
            }
            patch.setName(req.getName());
        }
        if (req.getDescription() != null) {
            patch.setDescription(req.getDescription());
        }
        workflowMapper.updateById(patch);
        return toVO(workflowMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        loadOwned(id);
        workflowMapper.deleteById(id);
        nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, id));
        edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, id));
        // 发布密钥随工作流失效（执行历史按契约保留）
        apiKeyMapper.delete(new LambdaQueryWrapper<WorkflowApiKey>()
                .eq(WorkflowApiKey::getWorkflowId, id));
    }

    /**
     * 整图全量替换：事务内先删后插，校验失败回滚。
     */
    @Transactional
    public void saveGraph(Long id, GraphSaveRequest req) {
        loadOwned(id);
        List<EdgeDTO> edges = req.getEdges() == null ? List.of() : req.getEdges();
        graphValidator.validate(req.getNodes(), edges);

        nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, id));
        edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, id));

        for (NodeDTO dto : req.getNodes()) {
            WorkflowNode node = new WorkflowNode();
            node.setWorkflowId(id);
            node.setNodeKey(dto.getNodeKey());
            node.setType(dto.getType());
            node.setName(dto.getName() == null ? "" : dto.getName());
            node.setConfig(dto.getConfig() == null ? "{}" : dto.getConfig().toString());
            node.setPositionX(dto.getPositionX() == null ? 0D : dto.getPositionX());
            node.setPositionY(dto.getPositionY() == null ? 0D : dto.getPositionY());
            nodeMapper.insert(node);
        }
        for (EdgeDTO dto : edges) {
            WorkflowEdge edge = new WorkflowEdge();
            edge.setWorkflowId(id);
            edge.setSourceNodeKey(dto.getSourceNodeKey());
            edge.setTargetNodeKey(dto.getTargetNodeKey());
            edge.setSourceHandle(dto.getSourceHandle());
            edgeMapper.insert(edge);
        }
    }

    public GraphVO getGraph(Long id) {
        loadOwned(id);
        List<NodeDTO> nodes = nodeMapper
                .selectList(new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, id)
                        .orderByAsc(WorkflowNode::getId))
                .stream()
                .map(this::toNodeDTO)
                .toList();
        List<EdgeDTO> edges = edgeMapper
                .selectList(new LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getWorkflowId, id)
                        .orderByAsc(WorkflowEdge::getId))
                .stream()
                .map(e -> {
                    EdgeDTO dto = new EdgeDTO();
                    dto.setSourceNodeKey(e.getSourceNodeKey());
                    dto.setTargetNodeKey(e.getTargetNodeKey());
                    dto.setSourceHandle(e.getSourceHandle());
                    return dto;
                })
                .toList();
        return new GraphVO(nodes, edges);
    }

    /**
     * 归属校验：不存在 404，他人所有 403。
     */
    private Workflow loadOwned(Long id) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
        }
        if (!workflow.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人的工作流");
        }
        return workflow;
    }

    private NodeDTO toNodeDTO(WorkflowNode node) {
        NodeDTO dto = new NodeDTO();
        dto.setNodeKey(node.getNodeKey());
        dto.setType(node.getType());
        dto.setName(node.getName());
        dto.setConfig(readTree(node.getConfig()));
        dto.setPositionX(node.getPositionX());
        dto.setPositionY(node.getPositionY());
        return dto;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            // 数据由本服务写入，正常不会发生
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "节点 config 解析失败");
        }
    }

    /** workflow 统一结构（契约 v1.2）：nodeCount 实时统计 */
    private WorkflowVO toVO(Workflow workflow) {
        WorkflowVO vo = new WorkflowVO();
        vo.setId(workflow.getId());
        vo.setUserId(workflow.getUserId());
        vo.setName(workflow.getName());
        vo.setDescription(workflow.getDescription());
        vo.setStatus(workflow.getStatus());
        vo.setCreatedAt(workflow.getCreatedAt());
        vo.setUpdatedAt(workflow.getUpdatedAt());
        vo.setNodeCount(nodeMapper.selectCount(
                new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, workflow.getId())));
        return vo;
    }
}
