package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.*;
import com.example.server.entity.Document;
import com.example.server.entity.DocumentChunk;
import com.example.server.entity.LearningSession;
import com.example.server.mapper.LearningProgressMapper;
import com.example.server.mapper.LearningSessionMapper;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class LearningSessionService {
    private final LearningSessionMapper sessionMapper;
    private final LearningProgressMapper progressMapper;
    private final DocumentService documentService;
    private final DocumentRetrievalService retrievalService;
    private final DeepSeekUtils deepSeekUtils;
    private final ObjectMapper objectMapper;
    private final TaskEventService events;

    public LearningSessionService(LearningSessionMapper sessionMapper, LearningProgressMapper progressMapper,
                                  DocumentService documentService, DocumentRetrievalService retrievalService,
                                  DeepSeekUtils deepSeekUtils, ObjectMapper objectMapper,TaskEventService events) {
        this.sessionMapper = sessionMapper;
        this.progressMapper = progressMapper;
        this.documentService = documentService;
        this.retrievalService = retrievalService;
        this.deepSeekUtils = deepSeekUtils;
        this.objectMapper = objectMapper;
        this.events=events;
    }

    @Transactional
    public LearningSessionView create(CreateLearningSessionRequest request, Long userId) {
        Document document = documentService.requireOwned(request.documentId(), userId);
        if (!Set.of("READY", "READY_WITH_WARNINGS").contains(document.getStatus())) {
            throw new IllegalStateException("文档尚未完成解析，暂时不能开始学习");
        }
        String point = request.goal().trim();
        MaterialResult generated = material(document, point, request.goal(), "");
        LearningMaterial material = generated.material();
        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setDocumentId(document.getId());
        session.setGoal(request.goal().trim());
        session.setStatus("WAITING_FOR_ANSWER");
        session.setCurrentKnowledgePoint(generated.plan().knowledgePoint());
        session.setNextAction("EVALUATE");
        session.setVersion(0L);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        List<AgentTraceStep> trace = new ArrayList<>();
        trace.add(step("PLANNER", "制定学习路径", generated.plan().teachingStrategy(), "DONE", generated.ai()?"AI":"FALLBACK"));
        trace.add(step("RETRIEVER", "检索教材证据", "已选取 " + material.evidence().size() + " 条原文证据", "DONE", "VECTOR"));
        trace.add(step("TUTOR", "生成讲解与测验", generated.ai()?"模型已根据证据生成个性化讲解":"模型不可用，已使用原文规则兜底", "DONE", generated.ai()?"AI":"FALLBACK"));
        trace.add(step("CRITIC", generated.critique().passed()?"证据校验通过":"已退回原文摘录",
                critiqueDetail(generated.critique(),generated.rounds()), "DONE", generated.ai()?"AI":"FALLBACK"));
        trace.add(step("EVALUATOR", "等待掌握检查", "提交答案后将诊断错误并决定下一步", "ACTIVE", "AI"));
        session.setStateJson(write(new SessionState(material, null, generated.ai()?"AI":"FALLBACK", "等待用户回答", trace, generated.plan(), initialPath(generated.plan()), List.of())));
        sessionMapper.insert(session);
        events.publishLearning(session.getId(),TaskStatus.of(TaskStatus.State.PROCESSING,"等待用户回答"),TaskStage.WAITING_FOR_ANSWER);
        return view(session);
    }

    public LearningSessionView get(Long id, Long userId) {
        return view(requireOwned(id, userId));
    }

    @Transactional
    public LearningSessionView answer(Long id, LearningAnswerRequest request, Long userId) {
        LearningSession session = requireOwned(id, userId);
        String cached = progressMapper.findEvaluation(id, request.idempotencyKey());
        if (cached != null) return view(session);
        requireVersion(session, request.sessionVersion());
        if (!"WAITING_FOR_ANSWER".equals(session.getStatus())) throw new IllegalStateException("当前步骤已评估，请先生成下一轮讲解");
        SessionState state = state(session);
        if (state.material() == null || state.material().quiz() == null) {
            throw new IllegalStateException("当前学习步骤没有待回答的问题");
        }
        Double savedMastery = progressMapper.findMastery(userId, session.getDocumentId(), session.getCurrentKnowledgePoint());
        double current = savedMastery == null ? 0 : savedMastery;
        events.publishLearning(id,TaskStatus.of(TaskStatus.State.PROCESSING,"Evaluator 正在诊断答案"),TaskStage.EVALUATING);
        EvaluationResult evaluated = evaluate(state.material().quiz(), request.answer(), current);
        LearningEvaluation evaluation = LearningLoopPolicy.evaluate(evaluated.evaluation(), current,
                !remaining(state).isEmpty() || !resume(state).isEmpty());
        String nextAction = evaluation.nextAction();
        progressMapper.insertAttempt(id, request.idempotencyKey(), session.getCurrentKnowledgePoint(),
                write(state.material().quiz()), request.answer(), write(evaluation), evaluation.score(),
                write(state.material().evidence()));
        progressMapper.upsertMastery(userId, session.getDocumentId(), session.getCurrentKnowledgePoint(),
                evaluation.masteryScore(), evaluation.correct() ? "[]" : write(List.of(evaluation.errorType())));
        session.setStatus("COMPLETE".equals(nextAction) ? "COMPLETED" : "STEP_COMPLETED");
        session.setNextAction(nextAction);
        String reason = "COMPLETE".equals(nextAction) ? "掌握度已达到 80%，完成当前目标"
                : "RETEACH".equals(nextAction) ? (evaluation.correct() ? "回答正确，继续巩固直到掌握度达到 80%"
                    : "检测到“" + evaluation.errorType() + "”，下一轮换一种方式讲解")
                : "REVIEW_PREREQUISITE".equals(nextAction) ? "检测到基础概念缺失，先补充前置知识"
                : "答案达到推进条件，进入下一层知识";
        List<AgentTraceStep> trace = new ArrayList<>(safeTrace(state));
        if (!trace.isEmpty()) trace.remove(trace.size()-1);
        trace.add(step("EVALUATOR", "诊断学习答案", evaluation.feedback(), "DONE", evaluated.ai()?"AI":"FALLBACK"));
        trace.add(step("ROUTER", "选择下一步：" + nextAction, reason, "COMPLETE".equals(nextAction)?"DONE":"ACTIVE", "RULE"));
        session.setStateJson(write(new SessionState(state.material(), evaluation,
                mergeMode(state.executionMode(), evaluated.ai()), reason, trace, state.plan(), remaining(state), resume(state))));
        updateWithVersion(session, request.sessionVersion());
        events.publishLearning(id,TaskStatus.of("COMPLETE".equals(nextAction)?TaskStatus.State.COMPLETED:TaskStatus.State.PROCESSING,reason),
                "COMPLETE".equals(nextAction)?TaskStage.COMPLETED:TaskStage.REPLANNING);
        return view(session);
    }

    @Transactional
    public LearningSessionView next(Long id, LearningNextRequest request, Long userId) {
        LearningSession session = requireOwned(id, userId);
        requireVersion(session, request.sessionVersion());
        if ("COMPLETED".equals(session.getStatus())) return view(session);
        SessionState previous = state(session);
        if (!"STEP_COMPLETED".equals(session.getStatus())) throw new IllegalStateException("请先完成当前测验");
        List<String> remaining = new ArrayList<>(remaining(previous));
        List<String> resume = new ArrayList<>(resume(previous));
        String focus = session.getCurrentKnowledgePoint();
        if ("ADVANCE".equals(session.getNextAction())) {
            focus = !resume.isEmpty() ? resume.remove(resume.size() - 1)
                    : !remaining.isEmpty() ? remaining.remove(0) : focus;
        } else if ("REVIEW_PREREQUISITE".equals(session.getNextAction()) && previous.plan() != null) {
            for (String prerequisite : previous.plan().prerequisites()) {
                Double mastery = progressMapper.findMastery(userId, session.getDocumentId(), prerequisite);
                if (!prerequisite.equals(focus) && !resume.contains(prerequisite)
                        && resume.size() < 3 && (mastery == null || mastery < .8)) {
                    resume.add(focus);
                    focus = prerequisite;
                    break;
                }
            }
        }
        String feedback = "上轮动作：" + session.getNextAction() + "；上轮诊断：" + write(previous.evaluation())
                + "；上轮讲解：" + previous.material().explanation()
                + "；若为重讲，请针对错误换用分步讲解或例子；不要改变当前知识点名称。";
        Document document = documentService.requireOwned(session.getDocumentId(), userId);
        events.publishLearning(id,TaskStatus.of(TaskStatus.State.PROCESSING,"正在重新规划与检索"),TaskStage.RETRIEVING);
        MaterialResult generated = material(document, focus, session.getGoal(), feedback);
        LearningMaterial material = generated.material();
        session.setCurrentKnowledgePoint(focus);
        session.setStatus("WAITING_FOR_ANSWER");
        session.setNextAction("EVALUATE");
        List<AgentTraceStep> trace = new ArrayList<>(safeTrace(previous));
        if (!trace.isEmpty() && "ACTIVE".equals(trace.get(trace.size()-1).status())) {
            AgentTraceStep active=trace.remove(trace.size()-1);
            trace.add(new AgentTraceStep(active.role(),active.title(),active.detail(),"DONE",active.mode()));
        }
        trace.add(step("RETRIEVER", "重新检索教材证据", "已围绕“" + focus + "”选取 " + material.evidence().size() + " 条证据", "DONE", "VECTOR"));
        trace.add(step("PLANNER", "调整学习路径", generated.plan().teachingStrategy(), "DONE", generated.ai()?"AI":"FALLBACK"));
        trace.add(step("TUTOR", "生成下一轮讲解", generated.ai()?"模型已根据学习结果调整讲解":"模型不可用，已使用原文规则兜底", "DONE", generated.ai()?"AI":"FALLBACK"));
        trace.add(step("CRITIC", generated.critique().passed()?"证据校验通过":"已退回原文摘录",
                critiqueDetail(generated.critique(),generated.rounds()), "DONE", generated.ai()?"AI":"FALLBACK"));
        trace.add(step("EVALUATOR", "等待再次检查", "回答新问题后继续诊断与路由", "ACTIVE", "AI"));
        session.setStateJson(write(new SessionState(material, previous.evaluation(),
                mergeMode(previous.executionMode(), generated.ai()), "等待用户回答", trace, generated.plan(), remaining, resume)));
        updateWithVersion(session, request.sessionVersion());
        events.publishLearning(id,TaskStatus.of(TaskStatus.State.PROCESSING,generated.ai() ? "新讲解已通过 Critic，等待回答" : "已切换为原文摘录，等待回答"),TaskStage.WAITING_FOR_ANSWER);
        return view(session);
    }

    private MaterialResult material(Document document, String point, String goal, String feedback) {
        List<DocumentChunk> chunks = retrievalService.retrieve(document.getId(), point + " " + goal, 4);
        if (chunks.isEmpty()) throw new IllegalStateException("没有找到足够的文档证据");
        LearningMaterial generated;
        LearningPlan plan;
        LearningCritique critique;
        int rounds=1;
        boolean ai=true;
        try {
            plan=deepSeekUtils.planLearning(goal, point + "\n" + feedback, chunks);
            if (plan == null || plan.knowledgePoint() == null || plan.knowledgePoint().isBlank())
                throw new IllegalStateException("Planner 未返回知识点");
            String target = feedback.isEmpty() ? plan.knowledgePoint().trim() : point;
            plan = new LearningPlan(target, plan.objectives(), plan.prerequisites(), plan.teachingStrategy(), plan.knowledgePoints());
            generated = deepSeekUtils.createLearningMaterial(goal,
                    target + "\nPlan:" + write(plan) + "\n学习反馈:" + feedback, chunks);
            critique = LearningLoopPolicy.verify(generated,
                    deepSeekUtils.critiqueLearningMaterial(goal,plan,generated,chunks), chunks);
            while (!critique.passed() && rounds < 3) {
                rounds++;
                generated=deepSeekUtils.repairLearningMaterial(goal,plan,generated,critique,chunks);
                critique=LearningLoopPolicy.verify(generated,
                        deepSeekUtils.critiqueLearningMaterial(goal,plan,generated,chunks), chunks);
            }
            if (!critique.passed()) {
                generated = fallbackMaterial(target, chunks);
                ai = false;
            }
        } catch (RuntimeException error) {
            generated = fallbackMaterial(point, chunks);
            plan=new LearningPlan(point,List.of(point),List.of(),"按检索到的原文顺序讲解");
            critique=new LearningCritique(false,List.of("模型不可用或输出无效，已退回原文摘录"),List.of(),List.of());
            ai=false;
        }
        List<DocumentEvidence> verified = ai ? generated.evidence().stream().map(citation -> {
            DocumentChunk chunk = chunks.stream().filter(c -> c.getChunkKey().equals(citation.chunkId())).findFirst().orElseThrow();
            return new DocumentEvidence(document.getId(), chunk.getChunkKey(), document.getFilename(),
                    chunk.getHeadingPath(), chunk.getPageNumber(), chunk.getSlideNumber(), chunk.getParagraphIndex(),
                    chunk.getLineStart(), chunk.getLineEnd(), citation.quote(), citation.supportedClaim());
        }).toList() : chunks.stream().map(chunk -> evidence(document, chunk)).toList();
        LearningMaterial.Quiz quiz = generated.quiz();
        String target = feedback.isEmpty() ? plan.knowledgePoint() : point;
        quiz = new LearningMaterial.Quiz(quiz.question(), quiz.referenceAnswer(), target);
        return new MaterialResult(new LearningMaterial(generated.title(), generated.explanation(), generated.keyPoints(), verified, quiz),ai,plan,critique,rounds);
    }

    private LearningMaterial fallbackMaterial(String point, List<DocumentChunk> chunks) {
        DocumentChunk first = chunks.get(0);
        String explanation = chunks.stream().map(DocumentChunk::getContent)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        if (explanation.length() > 1800) explanation = explanation.substring(0, 1800) + "…";
        String reference = first.getContent();
        return new LearningMaterial(point, explanation, List.of(reference), List.of(),
                new LearningMaterial.Quiz("请用自己的话说明本节核心内容。", reference, point));
    }

    private EvaluationResult evaluate(LearningMaterial.Quiz quiz, String answer, double current) {
        try {
            LearningEvaluation result = deepSeekUtils.evaluateLearningAnswer(quiz.question(), quiz.referenceAnswer(), answer, current);
            LearningLoopPolicy.evaluate(result, current, true);
            return new EvaluationResult(result,true);
        } catch (RuntimeException error) {
            Set<String> expected = terms(quiz.referenceAnswer());
            Set<String> actual = terms(answer);
            long matched = expected.stream().filter(actual::contains).count();
            double score = expected.isEmpty() ? 0 : (double) matched / expected.size();
            boolean correct = score >= 0.6;
            double mastery = clamp(current + (correct ? 0.3 : -0.1));
            return new EvaluationResult(new LearningEvaluation(correct, score,
                    correct ? "回答覆盖了教材中的主要信息。" : "回答与教材要点仍有差距，请结合原文再梳理一次。",
                    correct ? "NONE" : "INCOMPLETE", correct ? "ADVANCE" : "RETEACH", mastery),false);
        }
    }

    private DocumentEvidence evidence(Document document, DocumentChunk chunk) {
        String quote = chunk.getContent().length() > 320 ? chunk.getContent().substring(0, 320) : chunk.getContent();
        return new DocumentEvidence(document.getId(), chunk.getChunkKey(), document.getFilename(),
                chunk.getHeadingPath(), chunk.getPageNumber(), chunk.getSlideNumber(), chunk.getParagraphIndex(),
                chunk.getLineStart(), chunk.getLineEnd(), quote, quote);
    }

    private LearningSession requireOwned(Long id, Long userId) {
        LearningSession session = sessionMapper.selectById(id);
        if (session == null) throw new NoSuchElementException("学习会话不存在");
        if (!Objects.equals(session.getUserId(), userId)) throw new SecurityException("无权访问该学习会话");
        return session;
    }

    private void requireVersion(LearningSession session, long version) {
        if (!Objects.equals(session.getVersion(), version)) throw new IllegalStateException("学习状态已更新，请刷新后重试");
    }

    private void updateWithVersion(LearningSession session, long expected) {
        session.setVersion(expected + 1);
        session.setUpdatedAt(LocalDateTime.now());
        int updated = sessionMapper.update(session, new LambdaUpdateWrapper<LearningSession>()
                .eq(LearningSession::getId, session.getId()).eq(LearningSession::getVersion, expected));
        if (updated != 1) throw new IllegalStateException("学习状态已被其他请求更新");
    }

    private LearningSessionView view(LearningSession session) {
        SessionState state = state(session);
        return new LearningSessionView(session.getId(), session.getDocumentId(), session.getGoal(), session.getStatus(),
                session.getCurrentKnowledgePoint(), session.getNextAction(), session.getVersion(),
                state.material(), state.evaluation(), state.executionMode()==null?"UNKNOWN":state.executionMode(),
                state.decisionReason(), safeTrace(state), session.getCreatedAt(), session.getUpdatedAt());
    }

    private SessionState state(LearningSession session) {
        try { return objectMapper.readValue(session.getStateJson(), SessionState.class); }
        catch (Exception e) { throw new IllegalStateException("学习状态损坏", e); }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("学习状态保存失败", e); }
    }

    private Set<String> terms(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[\\s，。！？、,.;:：；!?]+"))
                .filter(value -> value.length() >= 2).collect(java.util.stream.Collectors.toSet());
    }

    private List<String> initialPath(LearningPlan plan) {
        return plan.knowledgePoints().stream().filter(p -> !p.equals(plan.knowledgePoint())).toList();
    }
    private List<String> remaining(SessionState state) { return state.remaining() == null ? List.of() : state.remaining(); }
    private List<String> resume(SessionState state) { return state.resume() == null ? List.of() : state.resume(); }

    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private AgentTraceStep step(String role,String title,String detail,String status,String mode){return new AgentTraceStep(role,title,detail,status,mode);}
    private List<AgentTraceStep> safeTrace(SessionState state){return state.trace()==null?List.of():state.trace();}
    private String mergeMode(String previous,boolean ai){return !ai||"FALLBACK".equals(previous)?"FALLBACK":"AI";}
    private String critiqueDetail(LearningCritique critique,int rounds){
        if(critique.passed())return "Critic 在第 "+rounds+" 轮确认讲解覆盖目标且有原文支持";
        return "Critic 未完全通过："+String.join("；",critique.feedback());
    }
    private record MaterialResult(LearningMaterial material,boolean ai,LearningPlan plan,LearningCritique critique,int rounds) { }
    private record EvaluationResult(LearningEvaluation evaluation,boolean ai) { }
    private record SessionState(LearningMaterial material, LearningEvaluation evaluation, String executionMode,
                                String decisionReason, List<AgentTraceStep> trace, LearningPlan plan,
                                List<String> remaining, List<String> resume) { }
}
