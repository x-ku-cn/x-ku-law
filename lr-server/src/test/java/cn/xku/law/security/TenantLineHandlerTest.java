package cn.xku.law.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证白名单策略：公共法规链路和平台侧表不注入 tenant_id，
 * 租户私有表保持注入。无需 Spring 上下文。
 */
class TenantLineHandlerTest {

    private TenantLineHandlerImpl handler;

    @BeforeEach
    void setUp() {
        handler = new TenantLineHandlerImpl();
    }

    @Test
    void lawPublicTablesShouldBeIgnored() {
        List<String> lawTables = List.of(
                "lr_law_document",
                "lr_law_version",
                "lr_law_article",
                "lr_law_article_segment",
                "lr_law_category",
                "lr_law_document_category",
                "lr_tag",
                "lr_law_document_tag",
                "lr_law_relation",
                "lr_law_citation",
                "lr_law_status_change"
        );
        for (String table : lawTables) {
            assertThat(handler.ignoreTable(table))
                    .as("公共法规表 %s 应在白名单中（不注入 tenant_id）", table)
                    .isTrue();
        }
    }

    @Test
    void systemGlobalTablesShouldBeIgnored() {
        List<String> systemTables = List.of(
                "lr_dict_type",
                "lr_dict_data",
                "lr_system_config",
                "lr_permission",
                "flyway_schema_history"
        );
        for (String table : systemTables) {
            assertThat(handler.ignoreTable(table))
                    .as("系统全局表 %s 应在白名单中", table)
                    .isTrue();
        }
    }

    @Test
    void platformOperationalTablesShouldBeIgnored() {
        List<String> platformTables = List.of(
                "lr_tenant",
                "lr_content_source",
                "lr_collect_task",
                "lr_import_batch",
                "lr_ai_model_config",
                "lr_prompt_template",
                "lr_job",
                "lr_banner",
                "lr_help_article"
        );
        for (String table : platformTables) {
            assertThat(handler.ignoreTable(table))
                    .as("平台运营表 %s 应在白名单中", table)
                    .isTrue();
        }
    }

    @Test
    void tenantPrivateTablesShouldNotBeIgnored() {
        List<String> privateTables = List.of(
                "lr_user",
                "lr_org",
                "lr_role",
                "lr_user_role",
                "lr_favorite",
                "lr_ai_session",
                "lr_ai_message",
                "lr_subscription_rule",
                "lr_compliance_task",
                "lr_ticket",
                "lr_notification",
                "lr_login_log",
                "lr_operation_log"
        );
        for (String table : privateTables) {
            assertThat(handler.ignoreTable(table))
                    .as("租户私有表 %s 不应在白名单中（需注入 tenant_id）", table)
                    .isFalse();
        }
    }

    @Test
    void whitelistSizeSanityCheck() {
        // 5(系统) + 12(法规，含 lr_law_interpretation) + 25(平台，含解析修复单) = 42 条
        assertThat(TenantLineHandlerImpl.TENANT_IGNORED_TABLES).hasSize(42);
    }

    @Test
    void subscriptionPlatformTablesShouldBeIgnored() {
        // lr_subscription_match / lr_alert_delivery 由平台事件触发生成，通过 rule_id 保证用户隔离
        assertThat(TenantLineHandlerImpl.TENANT_IGNORED_TABLES).contains("lr_subscription_match");
        assertThat(TenantLineHandlerImpl.TENANT_IGNORED_TABLES).contains("lr_alert_delivery");
        assertThat(TenantLineHandlerImpl.TENANT_IGNORED_TABLES).contains("lr_parse_repair_issue");
    }
}
