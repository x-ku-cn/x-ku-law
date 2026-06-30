<template>
  <section class="page page--full ai-page" :class="{ 'ai-page--ledger': showLedger }">
    <aside class="sessions">
      <div class="sessions-head">
        <div class="section-kicker">§ 会话</div>
        <button type="button" class="new-session" :disabled="streaming" @click="newConversation">＋ 新建对话</button>
      </div>
      <div class="sessions-body">
        <PageState v-if="error" :error="error" />
        <SkeletonSessions v-if="loading" :count="4" />
        <EmptyState v-else-if="!sessions.length" title="暂无会话" description="发起过的 AI 会话会在这里集中展示。" />
        <TransitionGroup v-else name="session" tag="div" class="session-list">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="session"
            :class="{ active: session.id === activeSessionId }"
            @click="selectSession(session.id)"
          >
            <span class="session-main">
              <strong>{{ session.title || `会话 ${session.id}` }}</strong>
              <span class="mono">{{ session.updateTime || session.createTime || '暂无时间' }}</span>
            </span>
            <button type="button" class="session-del" title="删除会话" @click.stop="removeSession(session.id)">✕</button>
          </div>
        </TransitionGroup>
      </div>
    </aside>

    <main class="opinion" :class="{ 'opinion--empty': showHero }">
      <section v-if="showHero" class="hero">
        <div class="hero-inner">
          <div class="section-kicker">§ X-KU 法规智询</div>
          <h1 class="hero-title display">{{ heroTitle }}</h1>
          <p class="hero-sub">提问越具体，检索越精准。</p>
          <div class="composer composer--hero">
            <div v-if="scopedLaw" class="composer-scope">
              <span class="scope-tag">
                <XChip tone="accent">{{ scopedLaw.title }}</XChip>
                <button type="button" class="scope-tag-remove" title="取消聚焦该法规" :disabled="streaming" @click="clearScopedLaw">✕</button>
              </span>
            </div>
            <XTextarea
              v-model="question"
              class="composer-input"
              :disabled="streaming"
              :rows="3"
              :placeholder="composerPlaceholder"
              @keydown.enter.exact.prevent="send"
            />
            <div class="composer-foot">
              <span class="composer-hint mono">Enter 发送 · Shift + Enter 换行</span>
              <XButton variant="primary" :loading="streaming" :disabled="!question.trim()" @click="send">发送问题</XButton>
            </div>
          </div>
          <div class="hero-samples">
            <button v-for="s in sampleQuestions" :key="s" type="button" class="sample" :disabled="streaming" @click="askSample(s)">{{ s }}</button>
          </div>
        </div>
      </section>

      <div v-else class="opinion-col">
        <header v-if="!isEmptyState" class="opinion-head">
          <div class="section-kicker">§ 法律意见</div>
          <button
            type="button"
            class="ledger-toggle"
            :class="{ active: ledgerOpen }"
            @click="ledgerOpen = !ledgerOpen"
          >
            <span>证据账册</span>
            <Transition name="count">
              <span v-if="totalCitations" class="ledger-count mono">{{ totalCitations }}</span>
            </Transition>
          </button>
        </header>

        <div ref="opinionEl" class="opinion-scroll">
          <PageState v-if="messageError" :error="messageError" />
          <SkeletonMessages v-else-if="messageLoading" :count="3" />
          <AiOpinionLayout
            v-else
            :messages="messages"
            :title="opinionTitle"
            :streaming="streaming"
            :stage-hint="toolHint"
            :show-answer-now="showAnswerNow"
            :active-cite-key="showLedger ? activeCiteKey : null"
            :user-label="userLabel"
            @answer-now="answerNow"
            @cite-click="onCite"
            @feedback="onFeedback"
            @toggle-like="onToggleLike"
          />
        </div>

        <section v-if="!isEmptyState" class="ask">
          <div class="composer">
            <div v-if="scopedLaw" class="composer-scope">
              <span class="scope-tag">
                <XChip tone="accent">{{ scopedLaw.title }}</XChip>
                <button type="button" class="scope-tag-remove" title="取消聚焦该法规" :disabled="streaming" @click="clearScopedLaw">✕</button>
              </span>
            </div>
            <XTextarea
              v-model="question"
              class="composer-input"
              :disabled="streaming"
              :rows="2"
              :placeholder="composerPlaceholder"
              @keydown.enter.exact.prevent="send"
            />
            <div class="composer-foot">
              <span class="composer-hint mono">Enter 发送 · Shift + Enter 换行</span>
              <XButton variant="primary" :loading="streaming" :disabled="!question.trim()" @click="send">发送问题</XButton>
            </div>
          </div>
        </section>
      </div>
    </main>

    <Transition name="ledger">
      <EvidenceLedger
        v-if="showLedger"
        v-model:active-key="activeCiteKey"
        :groups="citationGroups"
        @open-law="openCitationLaw"
        @close="ledgerOpen = false"
      />
    </Transition>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AiOpinionLayout from '@/components/business/AiOpinionLayout.vue';
import EvidenceLedger from '@/components/business/EvidenceLedger.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import PageState from '@/components/common/PageState.vue';
import SkeletonSessions from '@/components/common/SkeletonSessions.vue';
import SkeletonMessages from '@/components/common/SkeletonMessages.vue';
import XButton from '@/components/common/XButton.vue';
import XChip from '@/components/common/XChip.vue';
import XTextarea from '@/components/common/XTextarea.vue';
import { deleteAiSession, getAiMessages, getAiSessions } from '@/api/workspace';
import { askStream, stopAsk, submitAiFeedback, toggleAiLike } from '@/api/ai';
import { getLawDocument } from '@/api/law';
import { lawDetailTo } from '@/router/navigation';
import type { AiCitationGroup, AiMessage, AiMessageCitation, AiSession } from '@/types/workspace';
import { resolveApiError } from '@/utils/apiError';
import { useConfirm } from '@/composables/useConfirm';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const { confirm } = useConfirm();
const auth = useAuthStore();
const userLabel = computed(
  () => auth.profile?.username || auth.session?.username || '用户'
);
const seededQuestion = String(route.query.q || '').trim();
const pendingSeed = ref(seededQuestion.length > 0);
const sessionFromUrl = Number(route.query.session);
const hasSessionInUrl = Number.isFinite(sessionFromUrl) && sessionFromUrl > 0;
const loading = ref(false);
const error = ref('');
const sessions = ref<AiSession[]>([]);
const messages = ref<AiMessage[]>([]);
const activeSessionId = ref<number | null>(hasSessionInUrl ? sessionFromUrl : null);
const messageLoading = ref(hasSessionInUrl);
const messageError = ref('');
// 复合键 `${messageId}#${citeId}`：同一 [n] 跨回答指向不同条款，须按消息隔离。
const activeCiteKey = ref<string | null>(null);

const question = ref('');
const streaming = ref(false);
const toolHint = ref('');
const ANSWER_NOW_DELAY_MS = 7000;
const showAnswerNow = ref(false);
let stopStreamId: string | null = null;
let toolTimer: ReturnType<typeof setTimeout> | null = null;
function clearToolTimer() {
  if (toolTimer) {
    clearTimeout(toolTimer);
    toolTimer = null;
  }
}
const ledgerOpen = ref(false);

interface ScopedLaw {
  id: number;
  title: string;
}

const scopedLaw = ref<ScopedLaw | null>(null);

const composerPlaceholder = computed(() => {
  if (scopedLaw.value) {
    return isEmptyState.value
      ? `就《${scopedLaw.value.title}》提问…`
      : `继续就《${scopedLaw.value.title}》追问…`;
  }
  return isEmptyState.value ? '输入你的法律问题…' : '继续追问…';
});

async function syncScopedLawFromRoute() {
  const id = Number(route.query.documentId);
  if (!id) {
    scopedLaw.value = null;
    return;
  }
  const titleFromQuery = String(route.query.documentTitle || '').trim();
  if (titleFromQuery) {
    scopedLaw.value = { id, title: titleFromQuery };
    return;
  }
  try {
    const doc = await getLawDocument(id);
    if (Number(route.query.documentId) === id) {
      scopedLaw.value = { id, title: doc.title };
    }
  } catch {
    if (Number(route.query.documentId) === id) {
      scopedLaw.value = { id, title: `法规 #${id}` };
    }
  }
}

function clearScopedLaw() {
  scopedLaw.value = null;
  const rest = { ...route.query };
  delete rest.documentId;
  delete rest.documentTitle;
  router.replace({ query: rest });
}

watch(() => [route.query.documentId, route.query.documentTitle], syncScopedLawFromRoute, { immediate: true });

const heroTitles = [
  '带证据的法律问答',
  '有法可依的研究问答',
  '每一条结论，都有出处',
  '可溯源的法规解读',
  '检索先行，作答有据',
  '从法条出发的回答',
  '你的法规研究助理',
  '不作无据之答，不留悬空之判',
  '引用有法，判断可验'
];

const heroTitle = heroTitles[Math.floor(Math.random() * heroTitles.length)] ?? heroTitles[0];

const sampleQuestions = [
  '公司未与员工签订书面劳动合同，需承担什么法律后果？',
  '劳动合同试用期最长可以约定多久？',
  '消费者买到假冒商品可以主张几倍赔偿？',
  '股东出资不实需要承担哪些责任？'
];

const isEmptyState = computed(() => !messages.value.length);
const showHero = computed(() => !pendingSeed.value && isEmptyState.value && !messageLoading.value && !messageError.value);
const showLedger = computed(() => !isEmptyState.value && ledgerOpen.value);

const opinionEl = ref<HTMLElement | null>(null);
function isNearBottom(threshold = 120) {
  const el = opinionEl.value;
  if (!el) return true;
  return el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
}
function scrollToBottom(smooth = true) {
  const el = opinionEl.value;
  if (!el) return;
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
}
const totalCitations = computed(() => citationGroups.value.reduce((sum, g) => sum + g.citations.length, 0));

const activeSession = computed(() => sessions.value.find((s) => s.id === activeSessionId.value));

const opinionTitle = computed(() => {
  const title = activeSession.value?.title;
  return title ? `关于${title}的法律意见。` : '';
});

const citationGroups = computed<AiCitationGroup[]>(() => {
  const list = messages.value;
  const groups: AiCitationGroup[] = [];
  list.forEach((m, i) => {
    if (m.role !== 'assistant' || !m.citations?.length) return;
    let label = `第 ${groups.length + 1} 轮`;
    for (let j = i - 1; j >= 0; j--) {
      const q = list[j].role === 'user' ? list[j].content?.trim() : '';
      if (q) {
        label = q.length > 18 ? `${q.slice(0, 18)}…` : q;
        break;
      }
    }
    groups.push({ messageId: m.id, label, citations: m.citations });
  });
  return groups;
});

function citeKeyOf(messageId: number, citeId: string) {
  return `${messageId}#${citeId}`;
}

function onCite(payload: { messageId: number; citeId: string }) {
  activeCiteKey.value = citeKeyOf(payload.messageId, payload.citeId);
  ledgerOpen.value = true;
}

let activeStream: { abort: () => void } | null = null;
let streamSeq = 0;

function abortActiveStream() {
  if (!streaming.value) return;
  activeStream?.abort();
  activeStream = null;
  streamSeq += 1; // 作废在途回调
  streaming.value = false;
  toolHint.value = '';
  clearToolTimer();
  showAnswerNow.value = false;
  stopStreamId = null;
}

function newConversation() {
  abortActiveStream();
  activeSessionId.value = null;
  messages.value = [];
  activeCiteKey.value = null;
  ledgerOpen.value = false;
  question.value = '';
  messageError.value = '';
  if (route.query.session) router.replace({ query: { ...route.query, session: undefined } });
}

async function removeSession(id: number) {
  const confirmed = await confirm({
    title: '确认删除该会话？',
    message: '删除后不可恢复。',
    confirmText: '确认删除',
    danger: true
  });
  if (!confirmed) return;
  try {
    await deleteAiSession(id);
    sessions.value = sessions.value.filter((s) => s.id !== id);
    if (activeSessionId.value === id) newConversation();
  } catch (err) {
    error.value = resolveApiError(err, '删除会话失败。');
  }
}

async function selectSession(id: number, updateUrl = true) {
  abortActiveStream();
  activeSessionId.value = id;
  activeCiteKey.value = null;
  ledgerOpen.value = false;
  if (updateUrl && String(route.query.session || '') !== String(id)) {
    router.replace({ query: { ...route.query, session: String(id) } });
  }
  messageLoading.value = true;
  messageError.value = '';
  try {
    const result = await getAiMessages(id);
    messages.value = result.list;
  } catch (err) {
    messages.value = [];
    messageError.value = resolveApiError(err, '会话消息读取失败。');
  } finally {
    messageLoading.value = false;
  }
}

function openCitationLaw(cite: AiMessageCitation) {
  if (cite.documentId) {
    router.push(lawDetailTo(cite.documentId));
  }
}

async function onFeedback(payload: { messageId: number; feedbackType: string; content?: string }) {
  if (!payload.messageId || payload.messageId <= 0) return;
  try {
    await submitAiFeedback({
      messageId: payload.messageId,
      feedbackType: payload.feedbackType,
      feedbackContent: payload.content
    });
  } catch (err) {
    messageError.value = resolveApiError(err, '反馈提交失败，请稍后重试。');
  }
}

const likeToggleSeq = new Map<number, number>();

async function onToggleLike(messageId: number) {
  if (!messageId || messageId <= 0) return;
  const target = messages.value.find((m) => m.id === messageId);
  if (!target) return;

  const seq = (likeToggleSeq.get(messageId) ?? 0) + 1;
  likeToggleSeq.set(messageId, seq);
  const previous = !!target.liked;
  target.liked = !previous;

  try {
    const liked = await toggleAiLike(messageId);
    if (likeToggleSeq.get(messageId) === seq) {
      target.liked = liked;
    }
  } catch (err) {
    if (likeToggleSeq.get(messageId) === seq) {
      target.liked = previous;
      messageError.value = resolveApiError(err, '操作失败，请稍后重试。');
    }
  }
}

async function refreshSessions() {
  try {
    const result = await getAiSessions();
    sessions.value = result.list;
  } catch {
  }
}

function askSample(text: string) {
  if (streaming.value) return;
  question.value = text;
  send();
}

async function answerNow() {
  if (!stopStreamId || !streaming.value) return;
  showAnswerNow.value = false;
  clearToolTimer();
  toolHint.value = '整理已检索到的依据';
  try {
    await stopAsk(stopStreamId);
  } catch {
  }
}

async function send() {
  const text = question.value.trim();
  if (!text || streaming.value) return;
  const documentId = scopedLaw.value?.id;

  question.value = '';
  streaming.value = true;
  toolHint.value = '';
  messageError.value = '';
  showAnswerNow.value = false;
  stopStreamId = null;
  clearToolTimer();

  const tempBase = Date.now();
  messages.value.push(
    { id: -tempBase, clientKey: tempBase, sessionId: activeSessionId.value ?? 0, role: 'user', content: text }
  );
  const liveUser = messages.value[messages.value.length - 1];
  nextTick(() => scrollToBottom());

  await new Promise((resolve) => setTimeout(resolve, 240));
  messages.value.push(
    { id: -(tempBase + 1), clientKey: tempBase + 1, sessionId: activeSessionId.value ?? 0, role: 'assistant', content: '' }
  );
  const liveAssistant = messages.value[messages.value.length - 1];
  nextTick(() => scrollToBottom());

  // 本次流的序号：若用户中途切换会话，streamSeq 递增使 guard() 失效，
  // 后续迟到的回调（onDone/onError 等）便不再改写已切换的新会话状态。
  const seq = ++streamSeq;
  const guard = () => seq === streamSeq;

  activeStream = askStream(
    { sessionId: activeSessionId.value ?? undefined, question: text, documentId },
    {
      onMeta: (meta) => {
        if (!guard()) return;
        stopStreamId = meta.streamId;
        activeSessionId.value = meta.sessionId;
        liveUser.sessionId = meta.sessionId;
        liveUser.id = meta.userMessageId;
        liveAssistant.sessionId = meta.sessionId;
        if (String(route.query.session || '') !== String(meta.sessionId)) {
          router.replace({ query: { ...route.query, session: String(meta.sessionId) } });
        }
      },
      onTool: (event) => {
        if (!guard()) return;
        toolHint.value = event.summary;
        if (!toolTimer && !liveAssistant.content) {
          toolTimer = setTimeout(() => {
            if (guard() && streaming.value && !liveAssistant.content) showAnswerNow.value = true;
          }, ANSWER_NOW_DELAY_MS);
        }
      },
      onDelta: (chunk) => {
        if (!guard()) return;
        clearToolTimer();
        showAnswerNow.value = false;
        const stick = isNearBottom();
        liveAssistant.content = (liveAssistant.content || '') + chunk;
        if (stick) nextTick(() => scrollToBottom(false));
      },
      onDone: async (done) => {
        if (!guard()) return;
        liveAssistant.id = done.messageId;
        liveAssistant.citations = done.citations;
        liveAssistant.riskLevel = done.riskLevel;
        if (done.title && activeSessionId.value != null) {
          const s = sessions.value.find((item) => item.id === activeSessionId.value);
          if (s) s.title = done.title;
        }
        toolHint.value = '';
        streaming.value = false;
        activeStream = null;
        clearToolTimer();
        showAnswerNow.value = false;
        stopStreamId = null;
        await refreshSessions();
      },
      onAborted: async () => {
        // 流已开始但末段断开（多见于上线后外层代理未配 SSE）：后端通常已落库。
        // 静默地用持久化结果收尾，避免误报「连接中断」；仅在确实拉不到答案时才退回报错。
        if (!guard()) return;
        toolHint.value = '';
        activeStream = null;
        clearToolTimer();
        showAnswerNow.value = false;
        stopStreamId = null;
        const sid = activeSessionId.value;
        try {
          if (sid != null) {
            const result = await getAiMessages(sid);
            if (!guard()) return;
            messages.value = result.list;
          }
        } catch {
          /* 重载失败按下方逻辑统一处理 */
        }
        if (!guard()) return;
        streaming.value = false;
        const last = messages.value[messages.value.length - 1];
        if (!(last && last.role === 'assistant' && last.content)) {
          messageError.value = '连接中断，请重试。';
        }
      },
      onError: (msg) => {
        if (!guard()) return;
        toolHint.value = '';
        streaming.value = false;
        activeStream = null;
        clearToolTimer();
        showAnswerNow.value = false;
        stopStreamId = null;
        messageError.value = msg;
        if (!liveAssistant.content) {
          const idx = messages.value.findIndex((m) => m.id === liveAssistant.id);
          if (idx !== -1) messages.value.splice(idx, 1);
        }
      }
    }
  );
}

onMounted(async () => {
  if (seededQuestion && !streaming.value && activeSessionId.value == null) {
    const rest = { ...route.query };
    delete rest.q;
    router.replace({ query: rest });
    question.value = seededQuestion;
    send();
  }
  pendingSeed.value = false;

  loading.value = true;
  try {
    const result = await getAiSessions();
    sessions.value = result.list;
    if (!seededQuestion) {
      const fromUrl = Number(route.query.session);
      const initial = fromUrl ? sessions.value.find((s) => s.id === fromUrl) : undefined;
      if (initial) {
        await selectSession(initial.id, false);
      } else if (fromUrl) {
        activeSessionId.value = null;
        messageLoading.value = false;
        messageError.value = '会话不存在或已删除。';
      }
    }
  } catch (err) {
    if (!seededQuestion) error.value = resolveApiError(err, 'AI 会话读取失败。');
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.ai-page {
  display: grid;
  grid-template-columns: 276px minmax(0, 1fr) 0px;
  grid-template-rows: minmax(0, 1fr);
  height: 100%;
  overflow: hidden;
  transition: grid-template-columns 0.35s var(--ease);
}

.ai-page--ledger {
  grid-template-columns: 276px minmax(0, 1fr) 348px;
}

.ai-page :deep(.ledger) {
  min-width: 0;
  overflow: hidden;
}

.ledger-enter-active,
.ledger-leave-active {
  transition: opacity 0.35s var(--ease);
}

.ledger-enter-from,
.ledger-leave-to {
  opacity: 0;
}

.sessions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow: hidden;
  padding: 24px;
  border-right: 1px solid var(--rule);
  background: var(--paper-2);
}

.sessions-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-shrink: 0;
}

.sessions-body {
  display: grid;
  gap: 12px;
  align-content: start;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.sessions-body:hover {
  scrollbar-color: var(--rule) transparent;
}

.sessions-body::-webkit-scrollbar {
  width: 4px;
}

.sessions-body::-webkit-scrollbar-track {
  background: transparent;
}

.sessions-body::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 4px;
}

.sessions-body:hover::-webkit-scrollbar-thumb {
  background: var(--rule);
}

.sessions-body:hover::-webkit-scrollbar-thumb:hover {
  background: var(--muted-2);
}

.new-session {
  padding: 5px 10px;
  border: 1px solid var(--rule-strong);
  border-radius: 4px;
  background: var(--paper);
  color: var(--ink-2);
  font-size: var(--font-xxs);
  cursor: pointer;
  transition: border-color 0.15s var(--ease), color 0.15s var(--ease);
}

.new-session:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent-deep);
}

.new-session:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.session-list {
  display: grid;
  gap: 12px;
}

.session-enter-active {
  transition: opacity 0.32s var(--ease), transform 0.32s var(--ease);
}

.session-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}

.session-move {
  transition: transform 0.35s var(--ease);
}

.session-leave-active {
  transition: opacity 0.2s var(--ease);
}

.session-leave-to {
  opacity: 0;
}

.session {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper);
  color: var(--ink);
  cursor: pointer;
  text-align: left;
}

.session-main {
  display: grid;
  gap: 5px;
  min-width: 0;
  flex: 1;
}

.session-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-del {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s var(--ease), background 0.15s var(--ease), color 0.15s var(--ease);
}

.session:hover .session-del {
  opacity: 1;
}

.session-del:hover {
  background: var(--rose-soft, var(--accent-soft));
  color: var(--rose, var(--accent-deep));
}

.session.active {
  border-color: var(--accent-deep);
  background: var(--accent-soft);
}

.opinion {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 0 42px;
}

.opinion--empty {
  display: grid;
  align-content: center;
  justify-items: center;
  overflow-y: auto;
  padding: 24px 42px;
}

.opinion-col {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  max-width: 760px;
  margin-inline: auto;
}

.opinion-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
  padding: 28px 0 14px;
  border-bottom: 1px solid var(--rule);
}

/* 中段：唯一滚动区，承载消息/标题；隐藏滚动条但保留滚动 */
/* 左右各留 8px：overflow-y:auto 会令 overflow-x 计算为 auto，斜体衬线大标题的右侧出锋
   原本会被裁切，留出内边距即可避免字被切边。 */
.opinion-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 8px;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.opinion-scroll:hover {
  scrollbar-color: var(--rule) transparent;
}

.opinion-scroll::-webkit-scrollbar {
  width: 4px;
}

.opinion-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.opinion-scroll::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 4px;
}

.opinion-scroll:hover::-webkit-scrollbar-thumb {
  background: var(--rule);
}

.opinion-scroll:hover::-webkit-scrollbar-thumb:hover {
  background: var(--muted-2);
}

.ledger-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--rule-strong);
  border-radius: var(--radius-control);
  background: var(--paper);
  color: var(--ink-2);
  font-size: var(--font-xs);
  cursor: pointer;
  transition: border-color 0.15s var(--ease), background 0.15s var(--ease), color 0.15s var(--ease);
}

.ledger-toggle:hover {
  border-color: var(--accent);
  color: var(--accent-deep);
}

.ledger-toggle.active {
  border-color: var(--accent-deep);
  background: var(--accent-soft);
  color: var(--accent-deep);
}

.ledger-count {
  display: grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--paper-sunk);
  color: var(--ink-2);
  font-size: 10px;
}

.ledger-toggle.active .ledger-count {
  background: var(--accent-deep);
  color: #fff;
}

.count-enter-active {
  transition: opacity 0.25s var(--ease), transform 0.25s var(--ease);
}

.count-enter-from {
  opacity: 0;
  transform: scale(0.5);
}

.hero {
  display: flex;
  justify-content: center;
  width: 100%;
  padding: 24px 0;
}

.hero-inner {
  display: grid;
  gap: 18px;
  width: 100%;
  max-width: 640px;
  text-align: center;
}

.hero-title {
  margin: 0;
  font-size: clamp(30px, 4.5vw, 46px);
  line-height: 1.05;
}

.hero-sub {
  margin: 0;
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 16px;
  line-height: 1.65;
}

.composer {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--rule-strong);
  border-radius: var(--radius-control);
  background: var(--paper);
  text-align: left;
  transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease);
}

.composer:focus-within {
  border-color: var(--ink);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.06);
}

.composer--hero {
  margin-top: 6px;
}

.composer-scope {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
}

.scope-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.scope-tag :deep(.x-chip) {
  max-width: min(100%, 360px);
  overflow: hidden;
  text-overflow: ellipsis;
}

.scope-tag-remove {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background 0.15s var(--ease), color 0.15s var(--ease);
}

.scope-tag-remove:hover:not(:disabled) {
  background: var(--paper-sunk);
  color: var(--ink-2);
}

.scope-tag-remove:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}


.composer :deep(.composer-input) {
  min-height: 52px;
  padding: 4px 2px;
  border: 0;
  border-radius: 0;
  background: transparent;
  resize: none;
}

.composer :deep(.composer-input:focus) {
  border: 0;
}

.composer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.composer-hint {
  min-width: 0;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--font-xxs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-samples {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.sample {
  padding: 8px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--radius-control);
  background: var(--paper);
  color: var(--ink-2);
  font-size: var(--font-xs);
  cursor: pointer;
  transition: border-color 0.15s var(--ease), background 0.15s var(--ease), color 0.15s var(--ease);
}

.sample:hover:not(:disabled) {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-deep);
}

.sample:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ask {
  flex-shrink: 0;
  padding: 14px 0 18px;
  border-top: 1px solid var(--rule);
  background: var(--paper);
}

@media (max-width: 1120px) {
  .ai-page,
  .ai-page--ledger {
    grid-template-columns: 1fr;
    grid-template-rows: none;
    height: auto;
    overflow: visible;
  }

  .sessions {
    min-height: 0;
    overflow: visible;
    border-right: 0;
    border-bottom: 1px solid var(--rule);
  }

  .sessions-body {
    overflow: visible;
  }

  .opinion {
    min-height: 0;
    overflow: visible;
    padding: 0 24px;
  }

  .opinion-col {
    height: auto;
  }

  .opinion-scroll {
    overflow: visible;
  }
}
</style>
