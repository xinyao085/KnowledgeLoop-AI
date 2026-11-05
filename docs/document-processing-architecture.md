# 长文档任务与多模态处理

## 任务边界

上传将原文件保存到 MinIO、文档记录保存到 MySQL，DocumentTaskDispatcher 向 RocketMQ 发送 documentId。
DocumentTaskConsumer 同步调用 DocumentTaskExecutionService；Redis watchdog 锁防止同一文档并行处理。
处理异常向消费者传播，最多重投两次；FAILED 状态保留错误。投递失败的本地入口由 LocalDocumentTaskService
使用 documentTaskExecutor 异步执行，本地执行失败不承诺 MQ 重试，可从页面重新处理。

## 解析与并行处理

StructuredDocumentService 根据 document.parser 选择 legacy 或 MinerU 云端 v4 API。
TXT/Markdown 始终使用本地解析。MinerU 使用批量文件上传接口，不要求云端访问本机 MinIO。
解析结果（正文、表格、公式、图片引用和定位）持久化至 document_parse_states。
支持 v4 ZIP 的 content_list schema；未知 schema 明确失败，不静默丢弃内容。

解析完成后，独立 documentBranchExecutor 编排图片解释，与调用线程中的正文向量化并行。
图片理解由 documentImageExecutor 有界并发；每批最多 VISION_CONCURRENCY 个任务。
向量化由 embeddingExecutor 有界并发；每份文档最多 EMBEDDING_INDEX_CONCURRENCY 个在途任务。
模型请求设 HTTP 总超时；Embedding 网络错误、429、5xx 最多重试一次。线程池饱和时向量索引降级。
CompletableFuture 取消不能强制中断运行中的 HTTP 请求，运行请求仍受 HTTP 总超时约束。

成功图片解释逐批持久化；重新处理时跳过已保存解释。图片描述标记为 `[AI 图片解释]`，不是原文事实。
图片描述保存到独立 image_description 字段并参与检索，不写入原文 content，避免被逐字引用校验当作原文；image_refs_json 保留原图地址。图片缺少凭据或单图失败时为
READY_WITH_WARNINGS，正文仍可使用。所有索引失败时保留关键词检索。
目前消费会等待图片分支收敛；不提供“正文已就绪、图片仍处理中”的独立前端状态。

## 配置

默认 DOCUMENT_PARSER=legacy，不会向 MinerU 发送文件。
启用云端解析需 DOCUMENT_PARSER=mineru、MINERU_API_TOKEN。
图片解释需 VISION_API_KEY、VISION_BASE_URL（OpenAI 兼容接口根路径，包含 /v1）、VISION_MODEL（支持图片输入）。
上传的文档将发送给 MinerU，图片及附近正文发送给配置的视觉模型服务。
Docker Compose 与本地启动均支持这些变量，见 .env.example。数据库启动自动执行 V7 迁移。
启用 MinerU 后请新上传文档验证；已有解析检查点会优先复用，切换解析器不会自动使旧检查点失效。
已有文档的重新处理接口会置为 QUEUED，已保存的图片解释会被复用。

## 已知边界

MinerU API 返回图片规范化为 PNG；未知图片格式明确报错，不支持所有未来 schema。
DOCX 页码来自解析器，不保证与 Word 排版页码一致。
尚无图片装饰性分类器；当前对所有提取图片尝试解释。
云端接口尚需使用真实凭据进行端到端验证，单元测试不代表外部服务验证。
删除文档会清理已保存的解析检查点及图片，处理中禁止删除；解析检查点落库前失败可能留下孤立对象，需定期清理。失败的云端上传暂不支持远端任务 ID 断点恢复。
多实例时并发上限为各实例之和，并非供应商账号全局 QPS 限额。
