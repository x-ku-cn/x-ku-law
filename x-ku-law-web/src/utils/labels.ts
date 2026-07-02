/** 后端枚举 / 代码值 → 界面中文（与 schema、采集映射保持一致） */

const CODE_LABELS: Record<string, string> = {
  // 时效状态（status）
  effective: '现行有效',
  amended: '已修改',
  not_effective: '尚未生效',
  expired: '已失效',
  repealed: '已废止',
  unknown: '未知',

  // 版本时效（timeliness_status）
  current: '现行有效',
  history: '历史版本',

  // 法规类型
  law: '法律',
  regulation: '行政法规',
  rule: '部门规章',
  normative: '规范性文件',
  standard: '国家标准',
  policy: '政策',

  // 效力层级（检索 effectLevel / legalLevel 代码）
  national: '国家级',
  provincial: '省级',
  municipal: '市级',

  // 版本状态
  draft: '草稿',
  auditing: '审核中',
  published: '已发布',
  offline: '已下线',

  // 修订类型
  initial: '初始颁布',
  revised: '修订',
  // amended 已在时效

  // 法规关系
  amend: '修订',
  repeal: '废止',
  cite: '引用',
  interpret: '解释',
  support: '配套',
  conflict: '冲突',

  // 通用启用
  enabled: '启用',
  disabled: '停用',

  // 数据质量问题等级
  low: '低',
  normal: '一般',
  high: '高',
  critical: '严重',

  // 审核
  open: '待处理',
  pending: '待处理',
  pass: '已通过',
  rejected: '已驳回',
  reject: '已驳回',
  rollback: '已回退',

  // 处理 / 同步 / 采集
  processing: '处理中',
  running: '运行中',
  success: '成功',
  done: '已完成',
  failed: '失败',
  cancelled: '已取消',
  skipped: '已跳过',

  // 订阅与通知
  keyword: '关键词',
  document: '法规',
  inbox: '站内信',
  email: '邮件',
  realtime: '实时',
  daily: '每日',
  weekly: '每周',
  read: '已读',
  unread: '未读',

  // 通知类型（lr_notification.notification_type）
  system: '系统公告',
  alert: '预警提醒',
  audit: '审核通知',
  task: '任务提醒',
  ticket: '工单通知',

  // 通知发送范围（lr_notification.send_scope）
  single: '指定用户',
  role: '指定角色',
  tenant: '本租户',
  all: '全部用户',

  // 通知发送状态
  sent: '已发送',

  // 命中 / 变更
  new: '新增',
  update: '更新',
  ADDED: '新增',
  REMOVED: '删除',
  MODIFIED: '修改',
  UNCHANGED: '未变更',

  // 反馈类型（与 schema lr_feedback.feedback_type 对齐）
  data_error: '数据纠错',
  search_error: '检索问题',
  ai_error: 'AI 回答问题',
  function: '功能问题',
  suggestion: '建议',
  law_error: '法规纠错',
  feature: '功能问题',
  correction: '纠错',
  // 反馈处理状态
  resolved: '已处理',
  closed: '已关闭',

  // 分类
  subject: '主题',
  region: '地区',
  industry: '行业',
  legal_level: '效力层级',

  law_version: '法规版本',
  law_document: '法规文档',
  law_article: '条款',

  // AI / 场景
  qa: '问答',
  summary: '摘要',
  compare: '对比',
  compliance: '合规',
  drafting: '起草',
  active: '生效中',
  archived: '已归档',

  // 角色等
  admin: '管理员',
  user: '用户',

  // 检索排序
  relevance: '相关度',
  time_desc: '生效日期（新到旧）',
  time_asc: '生效日期（旧到新）'
};

const CJK_RE = /[\u4e00-\u9fff]/;

/**
 * 字典覆盖表（value→label）：由字典 store 在加载后注入，优先于内置 CODE_LABELS。
 * 字典缺失/未加载时为空，labelOf 自动回退到内置常量，公共页面与离线场景均安全。
 */
const dictOverride: Record<string, string> = {};

/** 由字典 store 调用：把字典的 dictValue→dictLabel 合并进覆盖表。 */
export function registerDictLabels(map: Record<string, string>): void {
  Object.assign(dictOverride, map);
}

/**
 * 将代码值转为中文标签；已是中文或未知代码则原样返回（空值显示 —）。
 * 解析顺序：字典覆盖表 → 内置 CODE_LABELS → 中文原样 → 下划线转空格。
 */
export function labelOf(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  const key = String(value).trim();
  const mapped =
    dictOverride[key] ?? dictOverride[key.toLowerCase()] ?? CODE_LABELS[key] ?? CODE_LABELS[key.toLowerCase()];
  if (mapped) return mapped;
  if (CJK_RE.test(key)) return key;
  return key.replace(/_/g, ' ');
}

export const timelinessOptions = [
  { label: '现行有效', value: 'effective' },
  { label: '已修改', value: 'amended' },
  { label: '尚未生效', value: 'not_effective' },
  { label: '已失效', value: 'expired' },
  { label: '已废止', value: 'repealed' },
  { label: '未知', value: 'unknown' }
];

export const lawTypeOptions = [
  { label: '法律', value: 'law' },
  { label: '行政法规', value: 'regulation' },
  { label: '部门规章', value: 'rule' },
  { label: '规范性文件', value: 'normative' },
  { label: '国家标准', value: 'standard' },
  { label: '政策', value: 'policy' }
];

export const effectLevelOptions = [
  { label: '法律', value: 'law' },
  { label: '行政法规', value: 'regulation' },
  { label: '部门规章', value: 'rule' },
  { label: '规范性文件', value: 'normative' },
  { label: '国家标准', value: 'standard' }
];

/**
 * 检索结果矩阵的效力层级展示顺序（labelOf 的中文显示值，与 effectLevelOptions 同一套词表）。
 * 层级语义集中在此一处，矩阵构建从这里取序与排除项，避免散落多文件后词表漂移。
 */
export const matrixLevelOrder = ['法律', '行政法规', '部门规章', '地方性法规', '司法解释'];

/** 矩阵不展示的效力层级（全国仅一部，单独占行无分布意义）。 */
export const matrixExcludedLevels = ['宪法'];

/** 矩阵展示时将同义/更名后的效力层级归并为一行（FLK flxz 曾用「地方法规」，现为「地方性法规」）。 */
export const matrixLevelAliases: Record<string, string> = {
  地方法规: '地方性法规'
};

/** 矩阵专用效力层级标签：先 labelOf，再按 matrixLevelAliases 归并。 */
export function matrixLevelOf(value: unknown): string {
  const label = labelOf(value);
  return matrixLevelAliases[label] ?? label;
}

export const versionStatusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '审核中', value: 'auditing' },
  { label: '已发布', value: 'published' },
  { label: '已下线', value: 'offline' }
];

export const revisionTypeOptions = [
  { label: '初始颁布', value: 'initial' },
  { label: '修订', value: 'revised' },
  { label: '修正', value: 'amended' },
  { label: '废止', value: 'repealed' }
];

/** 法规检索排序方式 */
export const searchSortOptions = [
  { label: '相关度', value: 'relevance' },
  { label: '生效日期（新→旧）', value: 'time_desc' },
  { label: '生效日期（旧→新）', value: 'time_asc' }
];

/**
 * 适用地区（省级行政区）。后端 region_code 实际存储省级行政区中文名，
 * 故选项 value 即为中文名，与索引值精确一致。与 FlkCodeTables.REGION_BY_ZDJG 保持同步。
 */
export const regionOptions = [
  '北京市', '天津市', '河北省', '山西省', '内蒙古自治区',
  '辽宁省', '吉林省', '黑龙江省', '上海市', '江苏省',
  '浙江省', '安徽省', '福建省', '江西省', '山东省',
  '河南省', '湖北省', '湖南省', '广东省', '广西壮族自治区',
  '海南省', '重庆市', '四川省', '贵州省', '云南省',
  '西藏自治区', '陕西省', '甘肃省', '青海省', '宁夏回族自治区',
  '新疆维吾尔自治区'
].map((name) => ({ label: name, value: name }));

export const relationTypeOptions = [
  { label: '修订', value: 'amend' },
  { label: '废止', value: 'repeal' },
  { label: '引用', value: 'cite' },
  { label: '解释', value: 'interpret' },
  { label: '配套', value: 'support' },
  { label: '冲突', value: 'conflict' }
];
