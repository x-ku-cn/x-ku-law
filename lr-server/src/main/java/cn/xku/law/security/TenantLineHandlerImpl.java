package cn.xku.law.security;

import cn.xku.law.common.security.SecurityUtils;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 多租户行级隔离处理器。白名单策略：默认对所有表注入 tenant_id，
 * 仅对公共法规主数据链路和平台运营侧表放行（不注入 tenant_id）。
 *
 * 白名单分三组，集中维护便于审计：
 *   SYSTEM_GLOBAL_TABLES   — 系统字典/配置等全局表
 *   PUBLIC_LAW_TABLES      — 法规公共主数据链路（tenant_id=0，全租户可见）
 *   PLATFORM_TABLES        — 平台运营/管理侧表（采集/导入/AI基础设施等）
 */
@Component
public class TenantLineHandlerImpl implements TenantLineHandler {

    /** 系统全局表：字典、配置、权限定义，无租户概念 */
    private static final Set<String> SYSTEM_GLOBAL_TABLES = Set.of(
            "lr_dict_type",
            "lr_dict_data",
            "lr_system_config",
            "lr_permission",
            "flyway_schema_history"
    );

    /**
     * 法规公共主数据链路：由平台统一录入，tenant_id=0，所有租户均可查阅。
     * 对应 docx §1「公共法规数据」+ §2.13「跨租户数据隔离」。
     */
    private static final Set<String> PUBLIC_LAW_TABLES = Set.of(
            "lr_law_document",
            "lr_law_version",
            "lr_law_article",
            "lr_law_article_segment",
            "lr_law_interpretation",
            "lr_law_category",
            "lr_law_document_category",
            "lr_tag",
            "lr_law_document_tag",
            "lr_law_relation",
            "lr_law_citation",
            "lr_law_status_change"
    );

    /**
     * 平台运营/管理侧表：由平台工作人员统一操作，tenant_id 均为 0，
     * 租户侧只读不写，无需按租户过滤。
     *
     * 特殊说明：
     *   lr_subscription_match / lr_alert_delivery — 由法规发布事件（平台操作）触发生成，
     *     逻辑上属于平台级记录；用户通过 rule_id（自身规则ID）过滤查询，避免跨用户数据泄露。
     *   PUBLIC_LAW_TABLES — 读操作在 Service 层手动加 (tenant_id=0 OR tenant_id=当前租户) 过滤，
     *     写操作由 RBAC 控制（需 law:document:create 权限，仅平台管理员持有）。
     */
    private static final Set<String> PLATFORM_TABLES = Set.of(
            // 采集 & 导入链路
            "lr_content_source",
            "lr_collect_task",
            "lr_collect_record",
            "lr_import_batch",
            "lr_import_record",
            "lr_raw_document",
            // 数据治理
            "lr_data_quality_issue",
            "lr_data_audit_record",
            "lr_parse_repair_issue",
            // AI / 检索基础设施
            "lr_search_index_task",
            "lr_vector_sync_task",
            "lr_law_process_task",
            "lr_law_ai_task",
            "lr_knowledge_chunk",
            "lr_ai_model_config",
            "lr_prompt_template",
            // 系统管理
            "lr_tenant",
            "lr_notification_template",
            "lr_job",
            "lr_job_log",
            "lr_system_metric_log",
            // 运营内容
            "lr_banner",
            "lr_help_article",
            "lr_subscription_match",
            "lr_alert_delivery"
    );

    /** 合并后的完整白名单，运行期不可变 */
    static final Set<String> TENANT_IGNORED_TABLES =
            Stream.of(SYSTEM_GLOBAL_TABLES, PUBLIC_LAW_TABLES, PLATFORM_TABLES)
                  .flatMap(Set::stream)
                  .collect(Collectors.toUnmodifiableSet());

    @Override
    public Expression getTenantId() {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        return new LongValue(tenantId != null ? tenantId : 0L);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return TENANT_IGNORED_TABLES.contains(tableName);
    }
}
