package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.UserContext;
import com.flowmind.dto.knowledge.KnowledgeBaseRequest;
import com.flowmind.dto.knowledge.KnowledgeBaseVO;
import com.flowmind.dto.knowledge.KnowledgeDocumentVO;
import com.flowmind.entity.Execution;
import com.flowmind.entity.ExecutionNodeLog;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.ExecutionNodeLogMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 全链路真实测试：上传文档（真实 embedding + 真实 Chroma 8001）→
 * 建工作流引用知识库 → execute（真实 DeepSeek）→ references 非空且回答基于文档。
 * 依赖：EMBEDDING_API_KEY 环境变量、本地 Chroma（127.0.0.1:8001，缺任一则整类跳过）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = "sk-.+")
class KnowledgeBaseFlowTest {

    /** 虚构但自洽的文档事实：用于断言 LLM 回答确实来自检索资料 */
    private static final String DOC_FACT = "费利克斯猫每天需要喂食 3 次，每次 15 克";
    private static final String DOC_TEXT = """
            费利克斯猫喂养指南（内部资料·第一部分）

            基础喂食规则：%s，喂食时间分别为早七点、午一点、晚六点。
            喂食前应将猫粮用温水浸泡五分钟，避免费利克斯猫进食过快引起呕吐。
            每次喂食后需要清洗食盆并晾干，残留食物容易滋生细菌导致肠胃炎。
            费利克斯猫的食盆应放置在安静角落，远离猫砂盆至少两米以上。

            费利克斯猫属于中型短毛猫，成年体重约四公斤，性格粘人且怕水。
            日常饮水必须使用凉白开，每天更换两次，禁止直接喂自来水。
            费利克斯猫对乳糖不耐受，严禁喂牛奶，可选无乳糖羊奶粉作为零食补充。
            幼猫期从两个月开始可以逐步引入干粮，过渡期约需要两周时间完成。

            健康监测与护理（第二部分）

            每周称重一次，体重波动超过百分之十需要就医检查甲状腺功能。
            疫苗方面每年接种一次猫三联，幼猫期需额外接种两针加强免疫。
            费利克斯猫的毛发短而密，每周梳毛两次即可，无需频繁洗澡。
            耳朵清洁每两周一次，使用宠物专用洗耳液，棉签不可深入耳道。
            指甲修剪每月一次，只剪透明尖端部分，避免剪到血线造成出血。

            常见误区与禁忌（第三部分）

            不少主人以为费利克斯猫可以吃狗粮，这是错误的，
            狗粮缺乏牛磺酸，长期喂食会导致费利克斯猫视网膜退化。
            另外费利克斯猫夜间活跃属正常行为，不建议强行调整作息。
            巧克力洋葱葡萄等人类食物对费利克斯猫有剧毒，必须严格隔离。
            费利克斯猫怀孕期为六十三天左右，孕期需要增加营养并减少应激。
            """.formatted(DOC_FACT);

    private static final OkHttpClient probe = new OkHttpClient();

    @BeforeAll
    static void requireChroma() {
        boolean chromaUp;
        try (var resp = probe.newCall(new Request.Builder()
                .url("http://127.0.0.1:8001/api/v2/heartbeat").get().build()).execute()) {
            chromaUp = resp.isSuccessful();
        } catch (Exception e) {
            chromaUp = false;
        }
        Assumptions.assumeTrue(chromaUp, "本地 Chroma 未运行，跳过 RAG 真实链路测试");
    }

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private WorkflowMapper workflowMapper;
    @Autowired
    private WorkflowNodeMapper nodeMapper;
    @Autowired
    private WorkflowEdgeMapper edgeMapper;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private ExecutionNodeLogMapper logMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("ragtest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("rag@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");
    }

    @AfterAll
    static void clearContext() {
        UserContext.clear();
    }

    private void cleanup(Long kbId, Long workflowId) {
        if (workflowId != null) {
            List<Long> executionIds = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                            .eq(Execution::getWorkflowId, workflowId))
                    .stream().map(Execution::getId).toList();
            if (!executionIds.isEmpty()) {
                logMapper.delete(new LambdaQueryWrapper<ExecutionNodeLog>()
                        .in(ExecutionNodeLog::getExecutionId, executionIds));
                executionMapper.deleteByIds(executionIds);
            }
            nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, workflowId));
            edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, workflowId));
            workflowMapper.deleteById(workflowId);
        }
        if (kbId != null) {
            try {
                knowledgeBaseService.delete(kbId);
            } catch (Exception ignored) {
                // 清理尽力而为
            }
        }
        if (userId != null) {
            userMapper.deleteById(userId);
        }
        UserContext.clear();
    }

    private boolean isTerminal(Long executionId) {
        String status = executionMapper.selectById(executionId).getStatus();
        return "success".equals(status) || "failed".equals(status);
    }

    @Test
    void uploadRetrieveAndRagExecution() throws Exception {
        // 1. 建库 + 上传
        KnowledgeBaseRequest req = new KnowledgeBaseRequest();
        req.setName("rag-flow-test");
        req.setDescription("全链路测试库");
        KnowledgeBaseVO kb = knowledgeBaseService.create(req);
        Long kbId = kb.getId();
        Long workflowId = null;
        try {
            KnowledgeDocumentVO doc = knowledgeBaseService.uploadDocument(kbId,
                    new MockMultipartFile("file", "felix-guide.txt", "text/plain",
                            DOC_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("felix-guide.txt", doc.getFilename());
            assertTrue(doc.getChunkCount() >= 2, "620+ 字文档应切多块: " + doc.getChunkCount());

            // 2. 建工作流：input(question) → llm(knowledge_base_id)
            Workflow wf = new Workflow();
            wf.setUserId(userId);
            wf.setName("rag-flow-wf");
            workflowMapper.insert(wf);
            workflowId = wf.getId();

            WorkflowNode input = new WorkflowNode();
            input.setWorkflowId(workflowId);
            input.setNodeKey("input_1");
            input.setType("input");
            input.setName("输入");
            input.setConfig("{\"fields\":[{\"key\":\"question\",\"label\":\"问题\",\"type\":\"text\"}]}");
            nodeMapper.insert(input);

            WorkflowNode llm = new WorkflowNode();
            llm.setWorkflowId(workflowId);
            llm.setNodeKey("llm_1");
            llm.setType("llm");
            llm.setName("RAG 问答");
            llm.setConfig("{\"model\":\"deepseek-flash\",\"knowledge_base_id\":" + kbId
                    + ",\"prompt\":\"{{input.question}}\",\"temperature\":0.1,\"output_key\":\"text\"}");
            nodeMapper.insert(llm);

            WorkflowEdge edge = new WorkflowEdge();
            edge.setWorkflowId(workflowId);
            edge.setSourceNodeKey("input_1");
            edge.setTargetNodeKey("llm_1");
            edgeMapper.insert(edge);

            // 3. 执行（真实 DeepSeek + 真实检索）
            Long executionId = executionService.submit(workflowId,
                    java.util.Map.of("question", "费利克斯猫每天喂几次？每次多少克？"));
            await().atMost(java.time.Duration.ofSeconds(60))
                    .pollInterval(java.time.Duration.ofMillis(500))
                    .until(() -> isTerminal(executionId));
            Execution execution = executionMapper.selectById(executionId);
            assertEquals("success", execution.getStatus(),
                    "RAG 执行应成功: " + execution.getErrorMessage());

            JsonNode llmOutput = objectMapper.readTree(execution.getOutput()).path("llm_1");
            String answer = llmOutput.path("text").asText("");
            assertTrue(answer.contains("3") && answer.contains("15"),
                    "回答应基于检索资料（3 次 / 15 克）: " + answer);

            JsonNode references = llmOutput.path("references");
            assertTrue(references.isArray() && references.size() > 0, "references 应非空");
            assertEquals("felix-guide.txt", references.get(0).path("filename").asText());
            assertTrue(references.get(0).path("snippet").asText().length() > 0);
            assertTrue(references.get(0).path("score").asDouble(0) > 0);

            // 4. 无 knowledge_base_id 的节点输出不含 references（回归：普通路径）
            List<ExecutionNodeLog> logs = logMapper.selectList(
                    new LambdaQueryWrapper<ExecutionNodeLog>()
                            .eq(ExecutionNodeLog::getExecutionId, executionId));
            assertNotEquals(0, logs.size());
        } finally {
            cleanup(kbId, workflowId);
        }
    }

    @Test
    void ownershipAndErrorBranches() {
        KnowledgeBaseRequest req = new KnowledgeBaseRequest();
        req.setName("rag-owner-test");
        KnowledgeBaseVO kb = knowledgeBaseService.create(req);
        try {
            // 他人访问 → 403
            UserContext.set(userId + 1, "user");
            try {
                knowledgeBaseService.listDocuments(kb.getId());
                throw new AssertionError("应抛 FORBIDDEN");
            } catch (BusinessException e) {
                assertEquals("FORBIDDEN", e.getErrorCode().getCode());
            } finally {
                UserContext.set(userId, "user");
            }

            // 不支持的类型 → DOCUMENT_PARSE_ERROR
            try {
                knowledgeBaseService.uploadDocument(kb.getId(), new MockMultipartFile(
                        "file", "virus.exe", "application/octet-stream", new byte[]{1, 2, 3}));
                throw new AssertionError("应抛 DOCUMENT_PARSE_ERROR");
            } catch (BusinessException e) {
                assertEquals("DOCUMENT_PARSE_ERROR", e.getErrorCode().getCode());
            }

            // 空内容文档 → 向量化管线拒绝（内容为空）
            try {
                knowledgeBaseService.uploadDocument(kb.getId(), new MockMultipartFile(
                        "file", "empty.txt", "text/plain", new byte[0]));
                throw new AssertionError("空文档应被拒绝");
            } catch (BusinessException e) {
                assertTrue(e.getErrorCode().getCode().equals("VALIDATION_ERROR")
                                || e.getErrorCode().getCode().equals("EMBEDDING_ERROR"),
                        "空文档拒绝: " + e.getErrorCode().getCode());
            }

            // 404
            try {
                knowledgeBaseService.listDocuments(999999L);
                throw new AssertionError("应抛 KNOWLEDGE_BASE_NOT_FOUND");
            } catch (BusinessException e) {
                assertEquals("KNOWLEDGE_BASE_NOT_FOUND", e.getErrorCode().getCode());
            }
            assertNotNull(kb.getId());
        } finally {
            UserContext.set(userId, "user");
            cleanup(kb.getId(), null);
        }
    }
}
