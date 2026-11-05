# 两层请求限流

业务入口通过 MVC 拦截器在认证后执行，教学（创建/提交答案/下一步）、单文档及课程问答、文档处理提交
分别维护用户级与全局 Redis RPM 配额。GET、SSE、上传分片不计入 AI 配额。
分片合并完成与直接上传属于解析提交入口，重试入口请求也计入配额。
这是入口保护，检查发生在业务幂等校验之前，重复请求也可能消耗许可。

LLM、Embedding、视觉模型在实际网络调用前分别获取 Redis 共享 RPM 许可和本机 Semaphore 并发许可。
LLM 许可在 modelCallExecutor 工作线程中获取、实际调用返回后释放；超时取消不会提前释放仍在运行调用的许可。
Embedding 重试再次计费；模型 SDK 内部隐藏的自动重试不单独统计。
视觉任务在 documentImageExecutor 中执行。Semaphore 为线程池之外的统一并发上限，不是用户级并发配额。

默认值：教学/问答用户 10 RPM，全局各 100 RPM；解析用户 3 RPM，全局 30 RPM；
LLM 60 RPM/实例并发 4，Embedding 120 RPM/实例并发 4，Vision 60 RPM/实例并发 2。
通过 .env.example、docker-compose.yml 和 application.properties 配置。
Redis 不可用时拒绝新调用；入口超额返回业务 429，服务故障返回 503。
模型超额沿用既有处理：文档问答降级、向量索引降级、图片保留待重试状态。

Redis 使用 Redisson RRateLimiter，用户与全局限额分别获取，不是跨两个 limiter 的原子事务。
用户许可可能在全局拒绝后已消耗；不承诺经典持续补充令牌桶的精确行为。
Redis 配额跨实例共享，并发限额是每实例生效，非全局并发配额；按供应商/密钥进一步隔离属于后续扩展。
原视频业务入口的 AnalysisDispatchService 继续保留原有独立限流，但实际 LLM 调用也受新模型配额限制。
