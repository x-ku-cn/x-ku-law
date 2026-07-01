package cn.xku.law.law.service;

import cn.xku.law.common.result.PageResult;
import cn.xku.law.law.domain.LawDocumentDO;
import cn.xku.law.law.domain.dto.LawDocumentCreateDTO;
import cn.xku.law.law.domain.dto.LawDocumentQueryDTO;
import cn.xku.law.law.domain.vo.LawDocumentVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;

/** 法规文件核心业务接口 */
public interface LawDocumentService extends IService<LawDocumentDO> {

    /** 分页查询法规（支持关键词 + 多维度过滤） */
    PageResult<LawDocumentVO> pageDocuments(LawDocumentQueryDTO query);

    /** 查询法规详情，不存在时抛出 AppException(LAW_DOCUMENT_NOT_FOUND) */
    LawDocumentVO getDocumentById(Long id);

    /** 新建法规主记录，返回新建 ID */
    Long createDocument(LawDocumentCreateDTO dto);

    /** 更新法规主记录元信息 */
    void updateDocument(Long id, LawDocumentCreateDTO dto);

    /** 逻辑删除法规（MyBatis-Plus 自动将 deleted 置为 1） */
    void removeDocument(Long id);

    /**
     * 按生效日期重算文档时效：在已发布版本中选出「现行版」并对齐 currentVersionId 及文档的
     * publish_date/effective_date/timeliness_status，同时按日期推导 status
     * （未生效→现行有效、现行→已失效）。与版本到达顺序无关，乱序/多批接入最终一致。
     *
     * <p>现行版取生效日期 &le; asOf 中最新者（并列取 id 最大）；无已生效版本时取最早的未来生效版本
     * （status=not_effective）；均无可判定生效日期时回退取公布日最新者且不动 status。
     * <b>源权威终态 repealed/amended 整条跳过，不自动改回。</b>
     * status 变更会写 lr_law_status_change 留痕；落库变更会重新入队检索索引（status 存于 ES）。
     *
     * @param asOf   判定基准日（定时/接入传 today）
     * @param dryRun true 时只计算不落库、不留痕、不入队，用于预演。
     */
    TimelinessReconcileResult reconcileTimeliness(Long documentId, LocalDate asOf, boolean dryRun);
}
