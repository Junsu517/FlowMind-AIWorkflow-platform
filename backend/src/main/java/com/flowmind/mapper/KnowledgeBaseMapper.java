package com.flowmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowmind.dto.knowledge.KnowledgeBaseVO;
import com.flowmind.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("""
            SELECT b.id, b.user_id, b.name, b.description, b.created_at,
                   (SELECT COUNT(*) FROM knowledge_documents d WHERE d.kb_id = b.id) AS document_count
            FROM knowledge_bases b
            WHERE b.user_id = #{userId}
            ORDER BY b.created_at DESC, b.id DESC
            """)
    IPage<KnowledgeBaseVO> selectPageWithDocumentCount(IPage<KnowledgeBaseVO> page,
                                                       @Param("userId") Long userId);
}
