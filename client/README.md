# KnowledgeLoop Web

KnowledgeLoop 的 Vue 3 文档学习工作台，包含文档上传、原文阅读、Learning Agent 教学与测验、连续问答和学习进度查看。

## 本地开发

先按[项目 README](../README.md) 启动后端与中间件，再在当前目录运行：

```bash
npm ci
VITE_API_BASE_URL=http://localhost:9090 npm run dev
```

浏览器访问 `http://localhost:5173`。当前 Vite 代理尚未包含文档与学习接口，因此本地开发显式设置 `VITE_API_BASE_URL`。后端地址改变时同步调整该变量；使用其他前端端口时，还需在后端设置对应的 `CORS_ALLOWED_ORIGINS`。

## 验证

```bash
npm test
npm run build
```

容器部署和模型配置见[项目 README](../README.md)。
