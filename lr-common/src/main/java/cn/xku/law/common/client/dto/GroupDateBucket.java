package cn.xku.law.common.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「分组字段 × 年份」聚合桶，对应一个 (group, year) 组合在全结果集中的命中数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupDateBucket {
    /** 分组字段取值，如 effectLevel 的某个层级 */
    private String group;
    /** 年份，由 date_histogram(calendar_interval=year) 得出 */
    private int year;
    /** 该 (group, year) 组合的命中数 */
    private long count;
}
