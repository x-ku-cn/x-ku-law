package cn.xku.law.law.mapper;

import cn.xku.law.law.domain.LawArticleDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** lr_law_article 数据访问层 */
@Mapper
public interface LawArticleMapper extends BaseMapper<LawArticleDO> {

    /**
     * 物理删除某版本的全部条款。分段阶段重跑前清场用：条款为可再生派生数据，
     * 须物理删除（逻辑删除会保留行占用 uk_article_no 唯一键，导致重插冲突）。
     * lr_law_article 在租户白名单内，不受行级租户过滤。
     */
    @Delete("DELETE FROM lr_law_article WHERE version_id = #{versionId}")
    int physicalDeleteByVersion(@Param("versionId") Long versionId);

    @Options(useGeneratedKeys = true, keyProperty = "articles.id")
    @Insert("""
            <script>
            INSERT INTO lr_law_article
            (document_id, version_id, parent_article_id, article_no, article_title,
             chapter_no, chapter_title, section_no, section_title, article_order, article_level,
             content_text, content_hash, obligation_flag, penalty_flag, status,
             creator, create_time, updater, update_time, deleted, tenant_id)
            VALUES
            <foreach collection="articles" item="item" separator=",">
              (#{item.documentId}, #{item.versionId}, #{item.parentArticleId}, #{item.articleNo}, #{item.articleTitle},
               #{item.chapterNo}, #{item.chapterTitle}, #{item.sectionNo}, #{item.sectionTitle}, #{item.articleOrder}, #{item.articleLevel},
               #{item.contentText}, #{item.contentHash}, #{item.obligationFlag}, #{item.penaltyFlag}, #{item.status},
               '', NOW(), '', NOW(), b'0', 0)
            </foreach>
            </script>
            """)
    int insertBatch(@Param("articles") List<LawArticleDO> articles);

    /**
     * 只更新单条的章/节归属列（存量回填用）。刻意仅触碰 chapter_no/chapter_title/section_no/section_title
     * 四列，不动 content_text/content_hash 与分片，故不会触发向量重嵌入。
     * timeout=60：单条（批量执行时为整批）超 60s 即抛异常，避免远程连接半死时无限阻塞。
     */
    @Options(timeout = 60)
    @Update("UPDATE lr_law_article SET chapter_no = #{chapterNo}, chapter_title = #{chapterTitle}, "
            + "section_no = #{sectionNo}, section_title = #{sectionTitle} WHERE id = #{id}")
    int updateChapterById(@Param("id") Long id,
                          @Param("chapterNo") String chapterNo,
                          @Param("chapterTitle") String chapterTitle,
                          @Param("sectionNo") String sectionNo,
                          @Param("sectionTitle") String sectionTitle);
}
