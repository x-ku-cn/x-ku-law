/** 章/节编号标记：第X章、第X节、第X编、第X篇 */
const CHAPTER_MARKER = '第\\s*[零一二三四五六七八九十百千两0-9]+\\s*[章节编篇]';
const CHAPTER_HEADING_RE = new RegExp(`^(${CHAPTER_MARKER})([\\s\\S]*)$`);

/** 把章号与章标题拆成两段，兼容标题粘连在 chapterNo 一列的存量数据。 */
export function splitChapterHeading(
  chapterNo?: string,
  chapterTitle?: string
): { no: string; title?: string } {
  const title = chapterTitle?.trim();
  const rawNo = chapterNo?.trim();
  if (!rawNo) return { no: '', title };
  if (title) return { no: rawNo, title };

  const m = rawNo.match(CHAPTER_HEADING_RE);
  if (m) {
    const no = m[1].replace(/\s+/g, '');
    const glued = m[2]?.trim();
    if (glued) return { no, title: glued };
  }
  return { no: rawNo };
}
