package cn.xku.law.law.serviceImpl;

import cn.xku.law.law.domain.LawVersionDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时效重算的纯决策逻辑单测（{@link LawDocumentServiceImpl#decide}）：覆盖未生效到达实施日期、
 * 到达失效日期、多版本按生效日期切现行版、未来版本不提前上位、无日期回退等。
 * repealed/amended 守卫在公共方法层拦截，不进入 decide，此处不覆盖。
 */
class LawTimelinessDecisionTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 1);

    private static LawVersionDO version(long id, LocalDate publish, LocalDate effective, LocalDate expire) {
        LawVersionDO v = new LawVersionDO();
        v.setId(id);
        v.setPublishDate(publish);
        v.setEffectiveDate(effective);
        v.setExpireDate(expire);
        return v;
    }

    @Test
    void notEffectiveBecomesEffectiveWhenDateArrives() {
        // 单版本，实施日期已过 → 现行有效。
        var v = version(1, LocalDate.of(2023, 12, 1), LocalDate.of(2024, 1, 1), null);
        var d = LawDocumentServiceImpl.decide(List.of(v), "not_effective", TODAY);
        assertThat(d.newStatus()).isEqualTo("effective");
        assertThat(d.current().getId()).isEqualTo(1);
    }

    @Test
    void stillNotEffectiveWhenDateInFuture() {
        // 实施日期在未来 → 尚未生效，现行版指向该未来版本。
        var v = version(1, LocalDate.of(2024, 5, 1), LocalDate.of(2024, 12, 1), null);
        var d = LawDocumentServiceImpl.decide(List.of(v), "not_effective", TODAY);
        assertThat(d.newStatus()).isEqualTo("not_effective");
        assertThat(d.current().getId()).isEqualTo(1);
    }

    @Test
    void effectiveBecomesExpiredWhenExpireDateArrives() {
        // 生效已过且失效日期已到 → 已失效。
        var v = version(1, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 3, 1), LocalDate.of(2024, 1, 1));
        var d = LawDocumentServiceImpl.decide(List.of(v), "effective", TODAY);
        assertThat(d.newStatus()).isEqualTo("expired");
    }

    @Test
    void multiVersionPicksLatestEffectiveAsCurrent() {
        // 2018 与 2023 双版本，今在 2024 → 现行版切到 2023、保持现行有效。
        var v2018 = version(1, LocalDate.of(2018, 8, 1), LocalDate.of(2018, 11, 1), null);
        var v2023 = version(2, LocalDate.of(2023, 10, 1), LocalDate.of(2024, 1, 1), null);
        var d = LawDocumentServiceImpl.decide(List.of(v2018, v2023), "effective", TODAY);
        assertThat(d.newStatus()).isEqualTo("effective");
        assertThat(d.current().getId()).isEqualTo(2);
    }

    @Test
    void futureVersionDoesNotBecomeCurrentPrematurely() {
        // 新版本已发布但生效日期在未来 → 现行版仍留旧版，不提前上位。
        var v2018 = version(1, LocalDate.of(2018, 8, 1), LocalDate.of(2018, 11, 1), null);
        var vFuture = version(2, LocalDate.of(2024, 5, 1), LocalDate.of(2027, 1, 1), null);
        var d = LawDocumentServiceImpl.decide(List.of(v2018, vFuture), "effective", TODAY);
        assertThat(d.newStatus()).isEqualTo("effective");
        assertThat(d.current().getId()).isEqualTo(1);
    }

    @Test
    void noParsableDateFallsBackToLatestPublishAndKeepsStatus() {
        // 均无生效日期 → 回退按公布日选现行版，status 保持原值（不臆造）。
        var a = version(1, LocalDate.of(2019, 1, 1), null, null);
        var b = version(2, LocalDate.of(2021, 1, 1), null, null);
        var d = LawDocumentServiceImpl.decide(List.of(a, b), "unknown", TODAY);
        assertThat(d.newStatus()).isEqualTo("unknown");
        assertThat(d.current().getId()).isEqualTo(2);
    }

    @Test
    void tieOnEffectiveDatePicksLargerId() {
        // 生效日期并列 → 取 id 最大者为现行版。
        var a = version(5, LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1), null);
        var b = version(9, LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1), null);
        var d = LawDocumentServiceImpl.decide(List.of(a, b), "effective", TODAY);
        assertThat(d.current().getId()).isEqualTo(9);
    }
}
