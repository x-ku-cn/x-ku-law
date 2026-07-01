package cn.xku.law.law.serviceImpl;

import cn.xku.law.common.constant.EffectLevelMapping;
import cn.xku.law.common.exception.AppException;
import cn.xku.law.common.exception.ErrorCode;
import cn.xku.law.common.result.PageResult;
import cn.xku.law.common.security.SecurityUtils;
import cn.xku.law.law.convert.LawDocumentConvert;
import cn.xku.law.law.domain.LawDocumentDO;
import cn.xku.law.law.domain.LawDocumentTagDO;
import cn.xku.law.law.domain.LawStatusChangeDO;
import cn.xku.law.law.domain.LawVersionDO;
import cn.xku.law.law.domain.TagDO;
import cn.xku.law.law.domain.dto.LawDocumentCreateDTO;
import cn.xku.law.law.domain.dto.LawDocumentQueryDTO;
import cn.xku.law.law.domain.vo.LawDocumentVO;
import cn.xku.law.law.mapper.LawDocumentMapper;
import cn.xku.law.law.mapper.LawDocumentTagMapper;
import cn.xku.law.law.mapper.LawStatusChangeMapper;
import cn.xku.law.law.mapper.LawVersionMapper;
import cn.xku.law.law.mapper.TagMapper;
import cn.xku.law.law.service.LawDocumentService;
import cn.xku.law.law.service.LawVersionService;
import cn.xku.law.law.service.TimelinessReconcileResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 法规文件业务实现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LawDocumentServiceImpl extends ServiceImpl<LawDocumentMapper, LawDocumentDO>
        implements LawDocumentService {

    private final LawDocumentConvert convert;
    private final LawVersionMapper lawVersionMapper;
    private final LawDocumentTagMapper lawDocumentTagMapper;
    private final TagMapper tagMapper;
    private final LawStatusChangeMapper lawStatusChangeMapper;
    private final LawVersionService lawVersionService;

    @Override
    public PageResult<LawDocumentVO> pageDocuments(LawDocumentQueryDTO query) {
        LambdaQueryWrapper<LawDocumentDO> wrapper = new LambdaQueryWrapper<LawDocumentDO>()
                .in(LawDocumentDO::getTenantId, allowedTenantIds())
                .like(StringUtils.hasText(query.getKeyword()),
                        LawDocumentDO::getTitle, query.getKeyword())
                .eq(StringUtils.hasText(query.getLawType()),
                        LawDocumentDO::getLawType, query.getLawType())
                // 前端传 code，legal_level 列存中文原值；展开为中文原值集合做 in 匹配
                .in(StringUtils.hasText(query.getLegalLevel()),
                        LawDocumentDO::getLegalLevel, EffectLevelMapping.toRawValues(query.getLegalLevel()))
                .eq(StringUtils.hasText(query.getStatus()),
                        LawDocumentDO::getStatus, query.getStatus())
                .eq(StringUtils.hasText(query.getRegionCode()),
                        LawDocumentDO::getRegionCode, query.getRegionCode())
                .like(StringUtils.hasText(query.getIssuingOrg()),
                        LawDocumentDO::getIssuingOrg, query.getIssuingOrg())
                .orderByDesc(LawDocumentDO::getCreateTime);

        IPage<LawDocumentDO> page = this.page(query.toPage(), wrapper);
        return PageResult.of(page.getTotal(), convert.toVOList(page.getRecords()));
    }

    @Override
    public LawDocumentVO getDocumentById(Long id) {
        LawDocumentDO doc = this.lambdaQuery()
                .eq(LawDocumentDO::getId, id)
                .in(LawDocumentDO::getTenantId, allowedTenantIds())
                .one();
        if (doc == null) {
            throw new AppException(ErrorCode.LAW_DOCUMENT_NOT_FOUND);
        }
        LawDocumentVO vo = convert.toVO(doc);
        vo.setTags(findTagNames(id));
        return vo;
    }

    /** 回挂文档关联的标签名（lr_law_document_tag → lr_tag）。无标签返回空列表。 */
    private List<String> findTagNames(Long documentId) {
        List<Long> tagIds = lawDocumentTagMapper.selectList(
                        new LambdaQueryWrapper<LawDocumentTagDO>()
                                .eq(LawDocumentTagDO::getDocumentId, documentId))
                .stream().map(LawDocumentTagDO::getTagId).toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectList(new LambdaQueryWrapper<TagDO>()
                        .in(TagDO::getId, tagIds))
                .stream().map(TagDO::getTagName).toList();
    }

    @Override
    public Long createDocument(LawDocumentCreateDTO dto) {
        if (lambdaQuery().eq(LawDocumentDO::getLawUid, dto.getLawUid()).exists()) {
            throw new AppException(ErrorCode.LAW_UID_DUPLICATE);
        }
        LawDocumentDO doc = convert.toDO(dto);
        doc.setTenantId(0L);
        this.save(doc);
        return doc.getId();
    }

    @Override
    public void updateDocument(Long id, LawDocumentCreateDTO dto) {
        LawDocumentDO doc = this.getById(id);
        if (doc == null) {
            throw new AppException(ErrorCode.LAW_DOCUMENT_NOT_FOUND);
        }
        convert.updateDO(dto, doc);
        this.updateById(doc);
    }

    @Override
    public void removeDocument(Long id) {
        if (!this.removeById(id)) {
            throw new AppException(ErrorCode.LAW_DOCUMENT_NOT_FOUND);
        }
    }

    @Override
    public TimelinessReconcileResult reconcileTimeliness(Long documentId, LocalDate asOf, boolean dryRun) {
        if (documentId == null) {
            return TimelinessReconcileResult.unchanged(null);
        }
        LawDocumentDO doc = this.getById(documentId);
        if (doc == null) {
            return TimelinessReconcileResult.unchanged(null);
        }
        String oldStatus = doc.getStatus();
        // 源权威终态：已废止/已修改（被新版取代的历史）不按日期自动改回。
        if ("repealed".equals(oldStatus) || "amended".equals(oldStatus)) {
            return TimelinessReconcileResult.unchanged(oldStatus);
        }
        List<LawVersionDO> published = lawVersionMapper.selectList(
                new LambdaQueryWrapper<LawVersionDO>()
                        .eq(LawVersionDO::getDocumentId, documentId)
                        .eq(LawVersionDO::getVersionStatus, "published"));
        if (published.isEmpty()) {
            return TimelinessReconcileResult.unchanged(oldStatus); // 尚无已发布版本，保持原状
        }

        LocalDate today = asOf != null ? asOf : LocalDate.now();

        Decision decision = decide(published, oldStatus, today);
        LawVersionDO current = decision.current();
        String newStatus = decision.newStatus();

        Long prevCurrentId = doc.getCurrentVersionId();
        boolean currentIdChanged = !Objects.equals(prevCurrentId, current.getId());
        boolean currentChanged = currentIdChanged
                || !Objects.equals(doc.getPublishDate(), current.getPublishDate())
                || !Objects.equals(doc.getEffectiveDate(), current.getEffectiveDate())
                || !"current".equals(doc.getTimelinessStatus());
        boolean statusChanged = newStatus != null && !Objects.equals(oldStatus, newStatus);

        if (!currentChanged && !statusChanged) {
            return TimelinessReconcileResult.unchanged(oldStatus);
        }
        if (dryRun) {
            return new TimelinessReconcileResult(true, oldStatus, newStatus, currentChanged);
        }

        doc.setCurrentVersionId(current.getId());
        doc.setPublishDate(current.getPublishDate());
        doc.setEffectiveDate(current.getEffectiveDate());
        doc.setTimelinessStatus("current");
        if (statusChanged) {
            doc.setStatus(newStatus);
        }
        this.updateById(doc);

        if (statusChanged) {
            LawStatusChangeDO change = new LawStatusChangeDO();
            change.setDocumentId(documentId);
            change.setOldStatus(oldStatus);
            change.setNewStatus(newStatus);
            change.setChangeReason(reasonOf(oldStatus, newStatus));
            change.setChangeDate(LocalDateTime.now());
            change.setOperatorUserId(null); // 系统自动，无操作人
            lawStatusChangeMapper.insert(change);
        }
        // status/现行版存于 ES（检索时效过滤与标注）：仅当 status 或现行版 id 真正变化才重入队索引，
        // 避免接入路径上 publishVersion 已入队后再重复入队（纯日期字段对齐不触发）。
        if (statusChanged || currentIdChanged) {
            lawVersionService.enqueueSearchIndex(current.getId());
        }

        return new TimelinessReconcileResult(true, oldStatus, newStatus, currentChanged);
    }

    /** 目标现行版 + 目标 status（纯计算，便于单测）。 */
    record Decision(LawVersionDO current, String newStatus) {
    }

    /**
     * 按生效日期从已发布版本中推导目标现行版与目标 status（不含 repealed/amended 守卫，调用方先拦）：
     * 生效日 &le; today 取最新者→effective（其失效日已到则 expired）；否则取最早未来版本→not_effective；
     * 均无可判定生效日期→回退公布日最新者且 status 保持原值。
     */
    static Decision decide(List<LawVersionDO> published, String oldStatus, LocalDate today) {
        LawVersionDO effectiveNow = null;
        for (LawVersionDO v : published) {
            LocalDate eff = v.getEffectiveDate();
            if (eff != null && !eff.isAfter(today)) {
                if (effectiveNow == null || eff.isAfter(effectiveNow.getEffectiveDate())
                        || (eff.isEqual(effectiveNow.getEffectiveDate()) && v.getId() > effectiveNow.getId())) {
                    effectiveNow = v;
                }
            }
        }
        if (effectiveNow != null) {
            boolean expired = effectiveNow.getExpireDate() != null
                    && !effectiveNow.getExpireDate().isAfter(today);
            return new Decision(effectiveNow, expired ? "expired" : "effective");
        }
        LawVersionDO upcoming = earliestUpcoming(published, today);
        if (upcoming != null) {
            return new Decision(upcoming, "not_effective");
        }
        // 无任何可判定生效日期：回退按公布日选现行版，status 保持原值（不臆造）。
        return new Decision(latestByPublishDate(published), oldStatus);
    }

    /** 未来生效版本里取生效日最早者（并列取 id 最小）；无则 null。 */
    private static LawVersionDO earliestUpcoming(List<LawVersionDO> published, LocalDate today) {
        LawVersionDO upcoming = null;
        for (LawVersionDO v : published) {
            LocalDate eff = v.getEffectiveDate();
            if (eff != null && eff.isAfter(today)) {
                if (upcoming == null || eff.isBefore(upcoming.getEffectiveDate())
                        || (eff.isEqual(upcoming.getEffectiveDate()) && v.getId() < upcoming.getId())) {
                    upcoming = v;
                }
            }
        }
        return upcoming;
    }

    /** 已发布版本里取公布日最新者（并列取 id 最大）；publishDate 为空视为最旧。 */
    private static LawVersionDO latestByPublishDate(List<LawVersionDO> published) {
        LawVersionDO latest = null;
        for (LawVersionDO v : published) {
            if (latest == null || comparePublish(v, latest) > 0) {
                latest = v;
            }
        }
        return latest;
    }

    private static int comparePublish(LawVersionDO a, LawVersionDO b) {
        LocalDate pa = a.getPublishDate();
        LocalDate pb = b.getPublishDate();
        if (!Objects.equals(pa, pb)) {
            if (pa == null) return -1;
            if (pb == null) return 1;
            return pa.compareTo(pb);
        }
        return Long.compare(a.getId(), b.getId());
    }

    private static String reasonOf(String oldStatus, String newStatus) {
        if ("effective".equals(newStatus)) return "auto:effective_date_arrived";
        if ("expired".equals(newStatus)) return "auto:expire_date_arrived";
        if ("not_effective".equals(newStatus)) return "auto:not_effective";
        return "auto:timeliness_recompute";
    }

    /** 公共法规（tenant_id=0）+ 当前租户私有法规（未来扩展用） */
    private List<Long> allowedTenantIds() {
        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        if (currentTenantId == null || currentTenantId == 0L) {
            return List.of(0L);
        }
        return Arrays.asList(0L, currentTenantId);
    }
}
