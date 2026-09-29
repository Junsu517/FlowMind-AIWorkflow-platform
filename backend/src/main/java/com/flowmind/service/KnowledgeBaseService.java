package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.UserContext;
import com.flowmind.dto.knowledge.KnowledgeBaseRequest;
import com.flowmind.dto.knowledge.KnowledgeBaseVO;
import com.flowmind.dto.knowledge.KnowledgeDocumentVO;
import com.flowmind.dto.workflow.PageVO;
import com.flowmind.entity.KnowledgeBase;
import com.flowmind.entity.KnowledgeDocument;
import com.flowmind.mapper.KnowledgeBaseMapper;
import com.flowmind.mapper.KnowledgeDocumentMapper;
import com.flowmind.service.rag.ChromaStoreService;
import com.flowmind.service.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final RagService ragService;
    private final ChromaStoreService chromaStoreService;

    public KnowledgeBaseVO create(KnowledgeBaseRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(UserContext.getUserId());
        kb.setName(req.getName());
        kb.setDescription(req.getDescription() == null ? "" : req.getDescription());
        kbMapper.insert(kb);
        // 预建 collection；Chroma 未起不阻断建库（上传/检索时会 ensure 兜底并返回 502）
        try {
            chromaStoreService.ensureCollection(kb.getId());
        } catch (Exception e) {
            log.warn("Chroma collection 预建失败（不阻断建库，上传时重试）: kbId={}, {}", kb.getId(), e.getMessage());
        }
        return toVO(kbMapper.selectById(kb.getId()));
    }

    public PageVO<KnowledgeBaseVO> list(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size < 1 ? 10 : Math.min(size, 50);
        var result = kbMapper.selectPageWithDocumentCount(
                new Page<>(safePage, safeSize), UserContext.getUserId());
        return new PageVO<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void delete(Long id) {
        loadOwned(id);
        List<KnowledgeDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKbId, id));
        documentMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKbId, id));
        kbMapper.deleteById(id);
        try {
            chromaStoreService.deleteCollection(id);
        } catch (Exception e) {
            // 数据行已删；collection 残留只占磁盘不影响正确性（名字含 kbId 不会复用），记录告警
            log.warn("Chroma collection 删除失败（残留不影响正确性）: kbId={}, {}", id, e.getMessage());
        }
        log.info("知识库已删除: kbId={}, documents={}", id, documents.size());
    }

    public KnowledgeDocumentVO uploadDocument(Long kbId, MultipartFile file) {
        loadOwned(kbId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        }
        String filename = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String text = extractText(filename, file);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setFilename(filename);
        doc.setFileSize((int) file.getSize());
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        int chunkCount;
        try {
            chunkCount = ragService.ingest(kbId, doc.getId(), filename, text);
        } catch (Exception e) {
            documentMapper.deleteById(doc.getId());
            throw new BusinessException(ErrorCode.EMBEDDING_ERROR,
                    "文档向量化/入库失败: " + e.getMessage());
        }
        doc.setChunkCount(chunkCount);
        documentMapper.updateById(doc);
        return toDocVO(documentMapper.selectById(doc.getId()));
    }

    public List<KnowledgeDocumentVO> listDocuments(Long kbId) {
        loadOwned(kbId);
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKbId, kbId)
                        .orderByDesc(KnowledgeDocument::getId))
                .stream().map(this::toDocVO).toList();
    }

    public void deleteDocument(Long kbId, Long docId) {
        loadOwned(kbId);
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_ERROR, "文档不存在");
        }
        // 先删向量（失败即 502 中止，避免残留 chunk 仍被检索到），再删元数据行
        try {
            chromaStoreService.deleteChunksByDoc(kbId, docId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.EMBEDDING_ERROR,
                    "向量删除失败: " + e.getMessage());
        }
        documentMapper.deleteById(docId);
    }

    private String extractText(String filename, MultipartFile file) {
        String lower = filename.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".pdf")) {
                try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
                    String text = new PDFTextStripper().getText(pdf);
                    if (text.isBlank()) {
                        throw new BusinessException(ErrorCode.DOCUMENT_PARSE_ERROR,
                                "PDF 未提取到文本（可能是扫描件）: " + filename);
                    }
                    return text;
                }
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_ERROR,
                    "不支持的文件类型（仅 txt/md/pdf）: " + filename);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_ERROR,
                    "文档读取失败: " + e.getMessage());
        }
    }

    private KnowledgeBase loadOwned(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!kb.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人的知识库");
        }
        return kb;
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId());
        vo.setUserId(kb.getUserId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setCreatedAt(kb.getCreatedAt());
        vo.setDocumentCount(documentMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKbId, kb.getId())));
        return vo;
    }

    private KnowledgeDocumentVO toDocVO(KnowledgeDocument doc) {
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        vo.setId(doc.getId());
        vo.setKbId(doc.getKbId());
        vo.setFilename(doc.getFilename());
        vo.setChunkCount(doc.getChunkCount());
        vo.setFileSize(doc.getFileSize());
        vo.setCreatedAt(doc.getCreatedAt());
        return vo;
    }
}
