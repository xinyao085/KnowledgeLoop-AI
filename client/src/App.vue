<template>
  <div class="shell">
    <aside class="rail">
      <a class="brand" href="#"><span>K</span><strong>KnowledgeLoop</strong></a>
      <nav><button :class="{active:activeView==='documents'}" @click="activeView='documents'">▦ <span>我的文档</span></button><button :class="{active:activeView==='dashboard'}" @click="openDashboard('dashboard')">◫ <span>今日学习</span></button><button :class="{active:activeView==='knowledge'}" @click="openDashboard('knowledge')">◎ <span>知识地图</span></button><button :class="{active:activeView==='reviews'}" @click="openDashboard('reviews')">↻ <span>待复习</span></button><button :class="{active:activeView==='wrong'}" @click="openDashboard('wrong')">! <span>错题本</span></button></nav>
      <div class="rail-foot"><div v-if="user" class="avatar">{{ user.nickname?.slice(0, 1) || 'K' }}</div><button v-if="user" class="text-button" @click="logout">退出登录</button><button v-else class="text-button" @click="showAuth = true">登录 / 注册</button></div>
    </aside>
    <main>
      <header><div><p class="eyebrow">KNOWLEDGE WORKSPACE</p><h1>把长文档，变成一条学得会的路。</h1></div><div class="status"><i></i>{{ polling ? '正在同步任务' : '服务已就绪' }}</div></header>
      <section v-if="activeView==='documents'" class="upload-card" :class="{ dragging }" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
        <div class="upload-copy"><span class="upload-icon">↑</span><div><h2>{{ uploading ? `${uploadPhase==='hashing'?'正在校验文件':uploadPhase==='merging'?'正在合并文档':'正在上传'} ${uploadProgress}%` : '投递一份新教材' }}</h2><p>PDF、DOCX、PPTX、TXT、Markdown · 最大 2 GiB · 中断后重新选择同一文件即可续传</p></div></div>
        <label class="primary" :class="{ disabled: uploading }">{{ uploading ? '处理中' : '选择文档' }}<input type="file" accept=".pdf,.docx,.pptx,.txt,.md,.markdown" hidden :disabled="uploading" @change="onPick" /></label>
        <button v-if="uploading" class="text-button" @click="uploadAbort?.abort()">暂停上传</button>
      </section>
      <p v-if="notice" class="notice" :class="{ error: noticeError }">{{ notice }}</p>
      <section v-if="activeView==='documents'" class="library">
        <div class="section-title"><div><p class="eyebrow">YOUR LIBRARY</p><h2>我的文档 <small>{{ documents.length }}</small></h2></div><input v-model="query" type="search" placeholder="搜索标题或文件名" /></div>
        <div v-if="!user" class="empty"><strong>登录后开始构建你的知识库</strong><p>学习进度、原文引用和掌握度会安全地保存在你的账户中。</p><button class="primary" @click="showAuth = true">现在登录</button></div>
        <div v-else-if="!filtered.length" class="empty"><strong>这里还没有文档</strong><p>上传第一份教材，KnowledgeLoop 会在后台完成解析与分块。</p></div>
        <div v-else class="document-grid">
          <article v-for="doc in filtered" :key="doc.id" @click="openDocument(doc)">
            <div class="doc-top"><span class="file-kind">{{ typeLabel(doc.documentType) }}</span><span class="state" :class="stateClass(doc.status)">{{ stateLabel(doc.status) }}</span></div>
            <div class="page-stack"><span></span><span></span><b>{{ doc.documentType === 'PDF' ? 'PDF' : 'TXT' }}</b></div>
            <h3>{{ doc.title || doc.filename }}</h3><p>{{ doc.filename }}</p>
            <footer><span>{{ doc.pageCount ? `${doc.pageCount} 页` : '结构化文本' }}</span><button @click.stop="removeDocument(doc)">删除</button></footer>
          </article>
        </div>
      </section>
      <section v-else class="library progress-page"><div class="section-title"><div><p class="eyebrow">LEARNING INSIGHTS</p><h2>{{ viewTitle }}</h2></div><button class="primary" @click="loadDashboard">刷新</button></div>
        <div v-if="!dashboard" class="empty"><strong>正在加载学习数据…</strong></div>
        <template v-else-if="activeView==='dashboard'"><div class="metric-grid"><article><strong>{{ dashboard.overview?.documentsStudied||0 }}</strong><span>已学习文档</span></article><article><strong>{{ dashboard.overview?.attempts||0 }}</strong><span>答题次数</span></article><article><strong>{{ percent(dashboard.overview?.averageMastery) }}</strong><span>平均掌握度</span></article><article><strong>{{ dashboard.overview?.dueCount||0 }}</strong><span>待复习</span></article></div></template>
        <div v-else class="insight-list"><article v-for="(item,index) in currentInsights" :key="index"><div><strong>{{ item.knowledgeKey||`错题 ${item.id}` }}</strong><p>{{ item.documentTitle }}</p></div><span>{{ item.masteryScore!=null?percent(item.masteryScore):percent(item.score) }}</span><p v-if="item.userAnswer">你的回答：{{ item.userAnswer }}</p></article><div v-if="!currentInsights.length" class="empty"><strong>目前没有内容</strong><p>完成一轮学习后，这里会自动生成。</p></div></div>
      </section>
    </main>
    <div v-if="selected" class="drawer-backdrop" @click.self="selected = null"><section class="drawer"><button class="drawer-close" @click="selected = null">×</button><p class="eyebrow">DOCUMENT READER</p><h2>{{ selected.title || selected.filename }}</h2><p class="drawer-meta">{{ stateLabel(selected.status) }} · {{ selected.filename }}</p>
      <div v-if="loadingContent" class="reader-empty">正在读取结构化原文…</div><div v-else-if="!isReady(selected)" class="reader-empty">文档正在后台解析，完成后即可开始学习。</div>
      <div v-else class="workspace-tabs"><button :class="{active:drawerMode==='learn'}" @click="drawerMode='learn'">Learning Agent</button><button :class="{active:drawerMode==='ask'}" @click="drawerMode='ask'">连续问答</button><button :class="{active:drawerMode==='read'}" @click="drawerMode='read'">阅读原文</button></div>
      <div v-if="isReady(selected) && drawerMode==='read'" class="reader"><aside><strong>章节与位置</strong><button v-for="chunk in chunks" :key="chunk.id" @click="activeChunk = chunk">{{ locator(chunk) }}</button></aside><article v-if="activeChunk"><span class="citation">{{ locator(activeChunk) }}</span><h3>{{ activeChunk.headingPath || '原文内容' }}</h3><p>{{ activeChunk.content }}</p></article></div>
      <div v-if="isReady(selected) && drawerMode==='learn'" class="learning-panel">
        <form v-if="!learning" class="learning-start" @submit.prevent="startLearning"><h3>这次想学会什么？</h3><p>Agent 会检索教材原文，先讲解，再用一道题检查掌握情况。</p><textarea v-model="learningGoal" required maxlength="1000" placeholder="例如：理解本章核心概念，并能用自己的话解释"></textarea><button class="primary" :disabled="learningBusy">{{ learningBusy?'正在准备学习材料…':'开始学习' }}</button></form>
        <template v-else>
          <div class="agent-console">
            <div class="agent-console-head"><div><p class="eyebrow">LIVE AGENT LOOP</p><h3>Agent 正在如何带你学习</h3></div><span class="mode-badge" :class="learning.executionMode?.toLowerCase()">{{ executionLabel(learning.executionMode) }}</span></div>
            <div class="agent-summary"><span>当前知识点</span><strong>{{ learning.currentKnowledgePoint }}</strong><span>路由决策</span><strong>{{ actionLabel(learning.nextAction) }}</strong></div>
            <ol class="agent-trace"><li v-for="(step,index) in learning.agentTrace" :key="`${index}-${step.role}`" :class="step.status?.toLowerCase()"><i>{{ index+1 }}</i><div><div><strong>{{ step.role }}</strong><span class="step-mode" :class="step.mode?.toLowerCase()">{{ step.mode }}</span></div><h4>{{ step.title }}</h4><p>{{ step.detail }}</p></div></li></ol>
            <div v-if="learningBusy" class="agent-working"><i></i><span>{{ liveAgentMessage || agentBusyText }}</span></div>
            <p v-if="learning.decisionReason" class="decision-reason"><strong>为什么这样决定：</strong>{{ learning.decisionReason }}</p>
          </div>
          <div class="learning-head"><div><span class="state ready">{{ statusLabel(learning.status) }}</span><h3>{{ learning.material?.title }}</h3></div><div class="mastery-meter"><span>掌握度 {{ masteryPercent }}</span><b><i :style="{width:masteryPercent}"></i></b></div></div>
          <div class="lesson-copy"><p>{{ learning.material?.explanation }}</p><ul><li v-for="point in learning.material?.keyPoints" :key="point">{{ point }}</li></ul></div><div class="evidence-list"><h4>教材证据</h4><button v-for="evidence in learning.material?.evidence" :key="evidence.chunkId" @click="showEvidence(evidence)"><strong>{{ evidence.pageNumber?`第 ${evidence.pageNumber} 页`:evidence.headingPath||`第 ${evidence.lineStart}–${evidence.lineEnd} 行` }}</strong><span>{{ evidence.quote }}</span></button></div><form v-if="learning.status==='WAITING_FOR_ANSWER'" class="quiz" @submit.prevent="submitAnswer"><h4>掌握检查</h4><p>{{ learning.material?.quiz?.question }}</p><textarea v-model="learningAnswer" required maxlength="4000" placeholder="用自己的话回答"></textarea><button class="primary" :disabled="learningBusy">{{ learningBusy?'Evaluator 正在诊断…':'提交答案' }}</button></form><div v-if="learning.lastEvaluation" class="evaluation" :class="{correct:learning.lastEvaluation.correct}"><strong>{{ learning.lastEvaluation.correct?'回答正确':'还需要巩固' }} · {{ Math.round(learning.lastEvaluation.score*100) }} 分</strong><p>{{ learning.lastEvaluation.feedback }}</p><small>错误类型：{{ learning.lastEvaluation.errorType }} · 下一步：{{ actionLabel(learning.nextAction) }}</small><button v-if="learning.status==='STEP_COMPLETED'" class="primary" :disabled="learningBusy" @click="nextLearning">{{ learning.nextAction==='RETEACH'?'让 Agent 换一种方式再讲':'让 Agent 继续下一步' }}</button><span v-else-if="learning.status==='COMPLETED'">当前目标已达到掌握阈值。</span></div>
        </template>
      </div>
      <div v-if="isReady(selected) && drawerMode==='ask'" class="learning-panel"><div class="qa-agent-banner"><div><p class="eyebrow">CONTEXTUAL RAG</p><strong>检索原文 → 结合对话历史 → AI 组织答案</strong></div><span>连续追问已启用</span></div><div class="chat-list"><article v-for="(message,index) in chat" :key="index" :class="message.role"><div class="message-head"><strong>{{ message.role==='user'?'你':'文档问答 Agent' }}</strong><span v-if="message.mode" class="mode-badge" :class="message.mode.toLowerCase()">{{ executionLabel(message.mode) }}</span></div><p>{{ message.text }}</p><div v-if="message.evidence" class="evidence-list"><button v-for="e in message.evidence" :key="e.chunkId" @click="showEvidence(e)"><strong>{{ e.pageNumber?`第 ${e.pageNumber} 页`:e.headingPath||'原文' }}</strong><span>{{ e.quote }}</span></button></div></article><div v-if="asking" class="qa-thinking"><i></i><span>Retriever 正在查找证据，Agent 正在结合上下文回答…</span></div></div><form class="quiz" @submit.prevent="askQuestion"><textarea v-model="question" required maxlength="4000" placeholder="基于这份教材提问，可连续追问"></textarea><button class="primary" :disabled="asking">{{ asking?'Agent 思考中…':'发送问题' }}</button></form></div>
    </section></div>
    <div v-if="showAuth" class="modal-backdrop" @click.self="showAuth = false"><form class="auth-card" @submit.prevent="authenticate"><button type="button" class="drawer-close" @click="showAuth = false">×</button><p class="eyebrow">WELCOME BACK</p><h2>{{ authMode === 'login' ? '继续你的学习' : '创建学习账户' }}</h2><label>用户名<input v-model="auth.username" required maxlength="32" /></label><label>密码<input v-model="auth.password" required type="password" maxlength="72" /></label><label v-if="authMode === 'register'">昵称<input v-model="auth.nickname" required maxlength="50" /></label><button class="primary" :disabled="authBusy">{{ authBusy ? '请稍候…' : (authMode === 'login' ? '登录' : '注册') }}</button><button type="button" class="switch" @click="authMode = authMode === 'login' ? 'register' : 'login'">{{ authMode === 'login' ? '没有账户？注册' : '已有账户？登录' }}</button></form></div>
  </div>
</template>
<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { uploadDocument } from './documentUpload.js'
import { apiRequest, clearAuthToken, hasAuthToken, setAuthToken } from './api'
const documents=ref([]),query=ref(''),selected=ref(null),chunks=ref([]),activeChunk=ref(null),drawerMode=ref('learn')
const learning=ref(null),learningGoal=ref(''),learningAnswer=ref(''),learningBusy=ref(false)
const liveAgentMessage=ref('')
const activeView=ref('documents'),dashboard=ref(null),chat=ref([]),question=ref(''),asking=ref(false),conversationId=ref(''),uploadProgress=ref(0),uploadPhase=ref('uploading')
const user=ref(JSON.parse(localStorage.getItem('knowledgeLoopUser')||'null'))
const uploading=ref(false),dragging=ref(false),notice=ref(''),noticeError=ref(false),showAuth=ref(false),authMode=ref('login'),authBusy=ref(false),loadingContent=ref(false),polling=ref(false)
const auth=ref({username:'',password:'',nickname:''});let pollTimer,eventAbort,learningEventAbort,uploadAbort
const filtered=computed(()=>documents.value.filter(doc=>`${doc.title||''} ${doc.filename}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const masteryPercent=computed(()=>Math.round((learning.value?.lastEvaluation?.masteryScore||0)*100)+'%')
const agentBusyText=computed(()=>learning.value?.status==='WAITING_FOR_ANSWER'?'Evaluator 正在分析答案并选择下一步…':'Retriever 与 Tutor 正在准备下一轮…')
const viewTitle=computed(()=>({dashboard:'今日学习',knowledge:'知识地图',reviews:'待复习',wrong:'错题本'}[activeView.value]||'学习中心'))
const currentInsights=computed(()=>({knowledge:dashboard.value?.knowledgeMap||[],reviews:dashboard.value?.dueReviews||[],wrong:dashboard.value?.wrongAnswers||[]}[activeView.value]||[]))
const percent=value=>`${Math.round(Number(value||0)*100)}%`
function flash(text,error=false){notice.value=text;noticeError.value=error;window.setTimeout(()=>{if(notice.value===text)notice.value=''},5000)}
async function loadDocuments(){if(!hasAuthToken())return;polling.value=true;try{const response=await apiRequest('/documents');if(response.ok)documents.value=await response.json()}finally{polling.value=false}}
async function loadDashboard(){if(!hasAuthToken())return;const response=await apiRequest('/learning/dashboard');if(response.ok)dashboard.value=await response.json()}
async function openDashboard(view){activeView.value=view;if(!user.value){showAuth.value=true;return}await loadDashboard()}
async function upload(file) {
  if (uploading.value) return
  if (!user.value) { showAuth.value=true; return }
  if (!file || !/\.(pdf|docx|pptx|txt|md|markdown)$/i.test(file.name)) return flash('支持 PDF、DOCX、PPTX、TXT 和 Markdown',true)
  uploading.value=true; uploadProgress.value=0; uploadPhase.value='hashing'
  uploadAbort=new AbortController()
  try {
    const document=await uploadDocument(file,user.value.id,{
      signal:uploadAbort.signal,
      onProgress:({phase,percent})=>{uploadPhase.value=phase;uploadProgress.value=percent}
    })
    documents.value=[document,...documents.value.filter(item=>item.id!==document.id)]
    flash('文档上传完成，后台将继续处理')
  } catch(error) {
    flash(error.name==='AbortError'?'上传已暂停，重新选择同一文件即可继续':`${error.message}；已保存的分片可在重新选择同一文件后继续上传`,error.name!=='AbortError')
  } finally { uploading.value=false }
}
function onPick(event){upload(event.target.files?.[0]);event.target.value=''}function onDrop(event){dragging.value=false;upload(event.dataTransfer?.files?.[0])}
async function openDocument(doc){eventAbort?.abort();selected.value=doc;chunks.value=[];activeChunk.value=null;learning.value=null;learningGoal.value='';chat.value=[];question.value='';conversationId.value='';drawerMode.value='learn';if(!isReady(doc)){watchProgress(doc);return}await loadDocumentContent(doc)}
async function loadDocumentContent(doc){loadingContent.value=true;try{const response=await apiRequest(`/documents/${doc.id}/content`);if(!response.ok)throw new Error(await response.text());chunks.value=await response.json();activeChunk.value=chunks.value[0]}catch(error){flash(error.message||'原文读取失败',true)}finally{loadingContent.value=false}}
async function watchProgress(doc){eventAbort=new AbortController();try{const response=await apiRequest(`/documents/${doc.id}/process-events`,{headers:{Accept:'text/event-stream'},signal:eventAbort.signal});if(!response.ok)return;const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='';while(true){const {done,value}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true});const events=buffer.split('\n\n');buffer=events.pop()||'';for(const block of events){const line=block.split('\n').find(item=>item.startsWith('data:'));if(!line)continue;const progress=JSON.parse(line.slice(5).trim());const current=documents.value.find(item=>item.id===doc.id);if(current)current.status=progress.stage;if(selected.value?.id===doc.id)selected.value={...selected.value,status:progress.stage};if(progress.progress>=100){await loadDocuments();const ready=documents.value.find(item=>item.id===doc.id);if(ready&&isReady(ready)&&selected.value?.id===doc.id){selected.value=ready;await loadDocumentContent(ready)}return}}}}catch(error){if(error.name!=='AbortError')console.warn('SSE progress stream closed',error)}}
async function startLearning(){learningBusy.value=true;try{const response=await apiRequest('/learning/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({documentId:selected.value.id,goal:learningGoal.value})});if(!response.ok)throw new Error(await response.text()||'学习会话创建失败');learning.value=await response.json()}catch(error){flash(error.message,true)}finally{learningBusy.value=false}}
async function connectLearningEvents(id){learningEventAbort?.abort();learningEventAbort=new AbortController();try{const response=await apiRequest(`/learning/sessions/${id}/events`,{headers:{Accept:'text/event-stream'},signal:learningEventAbort.signal});if(!response.ok)return;const reader=response.body.getReader(),decoder=new TextDecoder();void(async()=>{let buffer='';try{while(true){const {done,value}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true});const blocks=buffer.split('\n\n');buffer=blocks.pop()||'';for(const block of blocks){const line=block.split('\n').find(item=>item.startsWith('data:'));if(!line)continue;const event=JSON.parse(line.slice(5).trim());liveAgentMessage.value=event.message||stageLabel(event.stage)}}}catch(error){if(error.name!=='AbortError')console.warn('Learning SSE closed',error)}})()}catch(error){if(error.name!=='AbortError')console.warn('Learning SSE connection failed',error)}}
async function submitAnswer(){learningBusy.value=true;liveAgentMessage.value='正在连接 Agent 实时进度…';try{await connectLearningEvents(learning.value.id);const response=await apiRequest(`/learning/sessions/${learning.value.id}/answers`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sessionVersion:learning.value.version,answer:learningAnswer.value,idempotencyKey:crypto.randomUUID()})});if(!response.ok)throw new Error(await response.text()||'答案评价失败');learning.value=await response.json();learningAnswer.value=''}catch(error){flash(error.message,true)}finally{learningBusy.value=false;learningEventAbort?.abort();liveAgentMessage.value=''}}
async function nextLearning(){learningBusy.value=true;liveAgentMessage.value='正在连接 Agent 实时进度…';try{await connectLearningEvents(learning.value.id);const response=await apiRequest(`/learning/sessions/${learning.value.id}/next`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sessionVersion:learning.value.version})});if(!response.ok)throw new Error(await response.text()||'学习推进失败');learning.value=await response.json()}catch(error){flash(error.message,true)}finally{learningBusy.value=false;learningEventAbort?.abort();liveAgentMessage.value=''}}
async function askQuestion(){const text=question.value.trim();if(!text)return;chat.value.push({role:'user',text});question.value='';asking.value=true;try{const response=await apiRequest(`/documents/${selected.value.id}/questions`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({question:text,conversationId:conversationId.value||null})});if(!response.ok)throw new Error(await response.text()||'问答失败');const answer=await response.json();conversationId.value=answer.conversationId;chat.value.push({role:'assistant',text:answer.answer,evidence:answer.evidence,mode:answer.executionMode})}catch(error){flash(error.message,true)}finally{asking.value=false}}
function showEvidence(evidence){drawerMode.value='read';activeChunk.value=chunks.value.find(chunk=>chunk.chunkKey===evidence.chunkId)||activeChunk.value}
async function removeDocument(doc){if(!window.confirm(`删除“${doc.title||doc.filename}”？`))return;const response=await apiRequest(`/documents/${doc.id}`,{method:'DELETE'});if(!response.ok)return flash(await response.text()||'删除失败',true);documents.value=documents.value.filter(item=>item.id!==doc.id)}
async function authenticate(){authBusy.value=true;try{const response=await apiRequest(`/user/${authMode.value}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(auth.value)});if(!response.ok)throw new Error(await response.text()||'请求失败');const data=await response.json();setAuthToken(data.token);user.value=data.userInfo;localStorage.setItem('knowledgeLoopUser',JSON.stringify(user.value));showAuth.value=false;await loadDocuments()}catch(error){flash(error.message,true)}finally{authBusy.value=false}}
async function logout(){uploadAbort?.abort();try{await apiRequest('/user/logout',{method:'POST'})}finally{clearAuthToken();localStorage.removeItem('knowledgeLoopUser');user.value=null;documents.value=[];dashboard.value=null;activeView.value='documents'}}
const isReady=doc=>['READY','READY_WITH_WARNINGS'].includes(doc?.status)
const typeLabel=type=>({PDF:'PDF 教材',DOCX:'Word 文档',PPTX:'演示文稿',TXT:'纯文本',MARKDOWN:'Markdown'}[type]||type),stateLabel=state=>({QUEUED:'等待处理',EXTRACTING:'正在解析',CHUNKING:'正在分块',EMBEDDING:'正在建立索引',READY:'可学习',READY_WITH_WARNINGS:'可学习 · 关键词检索',FAILED:'解析失败'}[state]||state),stateClass=state=>isReady({status:state})?'ready':state==='FAILED'?'failed':'working'
const locator=chunk=>chunk.pageNumber?`第 ${chunk.pageNumber} 页`:chunk.headingPath||(chunk.lineStart?`第 ${chunk.lineStart}–${chunk.lineEnd} 行`:'原文块')
const executionLabel=mode=>({AI:'AI 模型运行',FALLBACK:'规则兜底',UNKNOWN:'历史会话'}[mode]||mode||'状态未知')
const actionLabel=action=>({EVALUATE:'等待回答',RETEACH:'重讲薄弱点',REVIEW_PREREQUISITE:'补充前置知识',ADVANCE:'进入下一层',COMPLETE:'完成目标'}[action]||action)
const stageLabel=stage=>({EVALUATING:'Evaluator 正在诊断答案',RETRIEVING:'Retriever 正在检索原文',REPLANNING:'Router 正在决定下一步',WAITING_FOR_ANSWER:'等待你的回答'}[stage]||stage)
const statusLabel=status=>({WAITING_FOR_ANSWER:'等待回答',STEP_COMPLETED:'本轮已评价',COMPLETED:'学习完成'}[status]||status)
onMounted(()=>{if(hasAuthToken())loadDocuments();pollTimer=window.setInterval(loadDocuments,5000)});onBeforeUnmount(()=>{window.clearInterval(pollTimer);uploadAbort?.abort();eventAbort?.abort();learningEventAbort?.abort()})
</script>
