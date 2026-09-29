package com.flowmind.controller;

import com.flowmind.dto.knowledge.KnowledgeBaseRequest;
import com.flowmind.dto.knowledge.KnowledgeBaseVO;
import com.flowmind.dto.knowledge.KnowledgeDocumentVO;
import com.flowmind.dto.workflow.PageVO;
import com.flowmind.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    public ResponseEntity<KnowledgeBaseVO> create(@Valid @RequestBody KnowledgeBaseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeBaseService.create(req));
    }

    @GetMapping
    public PageVO<KnowledgeBaseVO> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return knowledgeBaseService.list(page, size);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<KnowledgeDocumentVO> upload(@PathVariable Long id,
                                                      @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(knowledgeBaseService.uploadDocument(id, file));
    }

    @GetMapping("/{id}/documents")
    public Map<String, Object> listDocuments(@PathVariable Long id) {
        List<KnowledgeDocumentVO> items = knowledgeBaseService.listDocuments(id);
        return Map.of("items", items, "total", items.size());
    }

    @DeleteMapping("/{id}/documents/{docId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id, @PathVariable Long docId) {
        knowledgeBaseService.deleteDocument(id, docId);
        return ResponseEntity.noContent().build();
    }
}
