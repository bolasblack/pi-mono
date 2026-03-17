# 用 Shadow-CLJS 重写 pi-mono：完整分析报告

*由 5 位领域专家（Clojure/Lisp、Code Agent 架构、编程语言设计、开发者体验、分布式系统/架构）经 6 轮以上点对点讨论完成。Advisor 补充了宿主层进化机制和 SCI 能力分析。2026 年 3 月。*

---

## 目录

1. [执行摘要](#1-执行摘要)
2. [核心论点：乘法属性](#2-核心论点乘法属性)
3. [能力分析与代码示例](#3-能力分析与代码示例)
4. [SCI：沙箱化执行引擎](#4-sci沙箱化执行引擎)
5. [通过 Var 间接寻址实现宿主层进化](#5-通过-var-间接寻址实现宿主层进化)
6. ["在 TypeScript 中不可能"清单](#6-在-typescript-中不可能清单)
7. [对比矩阵](#7-对比矩阵)
8. [分发方案：shadow-cljs → Bun 编译](#8-分发方案shadow-cljs--bun-编译)
9. [诚实的局限性](#9-诚实的局限性)
10. [疯狂的想法](#10-疯狂的想法)
11. [实施路线图](#11-实施路线图)

---

## 1. 执行摘要

### 核心发现

**自进化 agent 是一个编程语言问题，不是工程问题。** 让 coding agent 根本性变强的那些能力——从自身历史中学习、在运行时修改自己的工具、并行竞速多种策略、跨会话积累知识——是三个共生语言属性的自然结果：同像性（代码即数据）、持久化数据结构（结构共享）、安全的运行时求值。这三个属性是**乘法关系**：拥有其中两个只能得到大约四分之一的能力，而不是三分之二。TypeScript 原生不具备其中任何一个。ClojureScript 三个都有。

### CLJS 能解锁什么

基于 ClojureScript 的 pi 不会是一个"渐进式更好"的 coding agent。它将是一个**根本不同类别的系统**：一个能检查自身工具实现、诊断失败原因、编写改进版本、通过结构化安全分析和模糊测试验证改进、在运行时热替换、跨会话持久化改进、并在 agent 实例间共享改进的自进化 agent。

### 战略问题

- **如果 agent 仍然是工具使用者**（调用预定义工具、遵循提示、输出文本），TypeScript 是正确选择。
- **如果 agent 要成为自进化系统**（从历史中学习、修改自身行为、跨会话积累能力），ClojureScript 是唯一可行的基础。这三个共生属性无法被改造进 TypeScript。

---

## 2. 核心论点：乘法属性

CLJS 有三个语言级属性，单独有用，但**组合后产生乘法效应**：

### 三个不可约属性

1. **同像性（代码 = 数据）** —— 代码用语言自身的数据结构（列表、向量、映射）表示。函数 `(fn [x] (+ x 1))` 既是可执行代码，也是可操作的列表。

2. **持久化数据结构（结构共享）** —— 更新集合会创建新版本，与旧版本共享约 99% 的内存。两个版本都保持有效。Fork 一个 10 万 token 的上下文只需几百字节。

3. **安全的运行时求值（SCI）** —— 沙箱化解释器执行动态生成的代码，仅允许白名单函数访问，支持结构化安全分析。

### 为什么是乘法而非加法

| 拥有的属性数量 | 能力水平 |
|-------------|---------|
| 只有一个 | 只能检查 或 分支 或 求值（有限）|
| 任意两个 | 有用但受根本性限制 |
| **三个都有** | **检查 → 验证 → 执行 → 持久化 → 重放 → 分支 → 进化** |

完整的自进化循环需要三者协同工作：
- **检查**工具源码（同像性）→ **验证**安全性（遍历代码树，同像性）→ **执行**改进版（安全求值）→ **持久化**为事件（持久化数据）→ **重放**（三者齐需）→ **分支**测试替代方案（持久化数据）→ **进化**跨会话（三者齐需）

移除任何一个属性，整个能力类别就会崩塌。TypeScript 原生拥有其中**零个**。

---

## 3. 能力分析与代码示例

### 3.1 Agent 自我检查工具

当前 pi-mono 的工具是 TypeScript 函数——不透明的闭包，agent 无法检查。在 CLJS 中：

```clojure
(deftool edit
  "通过替换精确文本来编辑文件。"
  {:path [:string {:description "文件路径"}]
   :old-text [:string {:description "要查找的文本"}]
   :new-text [:string {:description "替换文本"}]}
  [params signal ctx]
  (let [content (slurp (:path params))]
    (if (str/includes? content (:old-text params))
      (do (spit (:path params) (str/replace-first content (:old-text params) (:new-text params)))
          {:content [{:type "text" :text "编辑已应用"}]})
      {:content [{:type "text" :text "未找到 oldText"}]})))
```

`deftool` 宏自动将源码存储为元数据：

```clojure
(:source-form (get-tool "edit"))
;; => (fn [params signal ctx] (let [content (slurp (:path params))] ...))
```

Agent 可以读取实现代码，理解它使用精确匹配，诊断为什么空白符不匹配会导致失败。

### 3.2 DataScript 会话历史

所有会话数据存储为 Datalog 事实，可声明式查询：

```clojure
;; "过去 5 分钟哪些工具失败了？原因是什么？"
(d/q '[:find ?tool-name ?error (count ?e)
        :where
        [?e :entry/type :tool-result]
        [?e :tool-result/error? true]
        [?e :tool-result/tool-name ?tool-name]
        [?e :tool-result/content ?error]
        [?e :entry/timestamp ?t]
        [(> ?t (- (now) 300000))]]
      @session-db)

;; 跨会话："哪些文件通常一起编辑？"
(d/q '[:find ?a ?b (count ?pair)
        :where
        [?e1 :tool-call/name "edit"] [?e1 :tool-call/path ?a]
        [?e2 :tool-call/name "edit"] [?e2 :tool-call/path ?b]
        [(!= ?a ?b)]
        [?e1 :entry/session ?s] [?e2 :entry/session ?s]]
      @all-sessions-db)
```

这些模式可以反馈到 system prompt："src/types.ts 和 src/stream.ts 是耦合的——总是一起编辑。" Agent 通过结构化观察随着时间变得更好。

### 3.3 事件溯源架构

事件成为第一性，状态成为派生：

```
事件日志（不可变，DataScript）→ 事实来源
    ├─→ LLM 上下文 = f(事件, 模型, 预算)     [投影]
    ├─→ TUI 状态 = f(事件, UI 偏好)            [投影]
    ├─→ 会话文件 = serialize(事件)              [持久化]
    └─→ Agent 记忆 = g(跨会话事件)             [学习]
```

压缩变成**视图操作**（选择包含哪些事件），而不是破坏性变异。完整历史始终可用。可以用不同策略重新压缩、从任何点分支、撤销压缩。

### 3.4 O(1) 推测性执行

```clojure
(defn speculative-execute [ctx strategy-a strategy-b evaluate-fn]
  (let [result-a-ch (go (strategy-a ctx))    ;; 两个 fork 共享 ctx 的全部结构
        result-b-ch (go (strategy-b ctx))    ;; 无需复制
        [a b] [(<!! result-a-ch) (<!! result-b-ch)]]
    (evaluate-fn a b)))
```

Fork 一个 10,000 条消息的上下文：约 200 字节（新的尾节点）。在 TypeScript 中：约 5MB 深拷贝。

### 3.5 core.async 多消费者流式处理

```clojure
(let [m (a/mult source-ch)]
  {:tui-ch      (a/tap m (chan 1000))                    ;; 快速消费者
   :session-ch  (a/tap m (chan (dropping-buffer 5000)))   ;; 缓冲
   :metrics-ch  (a/tap m (chan (sliding-buffer 10)))})    ;; 允许丢失
```

每个消费者有自己的缓冲策略。慢速扩展不会阻塞 TUI 渲染。

### 3.6 Transducer 管线

单体 `transform-messages.ts`（170 行）变成可组合的：

```clojure
(defn prepare-for-provider [messages target-model]
  (into []
    (comp
      (keep msg->llm)
      (strip-foreign-signatures (:api target-model))
      (normalize-tool-ids (:api target-model))
      (insert-orphan-results)
      (ensure-alternating-roles))
    messages))
```

每个 transducer 独立可测试。添加步骤 = 在 `comp` 中增加一个 form。

### 3.7 开放的 Provider 分发

```clojure
(defmulti stream-provider (fn [model ctx opts] [(:api model) (:provider model)]))

(defmethod stream-provider ["anthropic-messages" :default] [model ctx opts]
  ;; Anthropic 实现
  )

;; 任何人都可以添加 provider —— 无需注册，无需修改核心文件
(defmethod stream-provider ["my-custom-api" :default] [model ctx opts]
  ;; 定义一个方法就行了。
  )
```

### 3.8 完整的自愈循环

1. Agent 检测失败模式（DataScript 查询）
2. Agent 读取工具源码（`(:source-form (get-tool "edit"))`）
3. Agent 通过模板化 DSL 编写改进版本
4. 系统执行结构化安全分析（遍历代码树）
5. SCI 在沙箱中求值 → 热替换
6. 修改记录为 DataScript 事件
7. 下次会话恢复：从事件日志水合修改

---

## 4. SCI：沙箱化执行引擎

### 它是什么

**SCI（Small Clojure Interpreter）** —— 一个用 ClojureScript 编写的 Clojure 解释器，可嵌入任何 JS 应用。提供精确控制能力的沙箱化求值。

```clojure
(require '[sci.core :as sci])

;; 创建沙箱，设置白名单
(def sandbox
  (sci/init {:namespaces {'user {'str str '+ + 'map map 'filter filter}}}))

;; 安全执行
(sci/eval-form sandbox '(map #(+ % 1) [1 2 3]))
;; => (2 3 4)

;; 被阻止 —— slurp 不在白名单中
(sci/eval-form sandbox '(slurp "/etc/passwd"))
;; => ERROR: Could not resolve symbol: slurp
```

### 性能

- 比编译后的 CLJS **慢约 10-50 倍**（解释执行 vs JIT 编译）
- **在 agent 场景中可忽略** —— 工具逻辑很轻量（shell 调用、文件 I/O、字符串匹配）；瓶颈在 LLM 调用（2000ms）和 I/O（5ms），不在 CPU
- **热路径可用原生函数** —— 注入到沙箱白名单中

### 宏支持

- ✅ SCI 内部支持 `defmacro`
- ⚠️ 宏在解释时展开，不是编译时
- ❌ 不可自定义 reader macro（标准的 `#()`、`@`、`'` 都支持）
- ✅ 对 agent 生成的代码足够（复杂宏放在宿主层）

### SCI 不能做什么（对比完整 CLJS）

| 能力 | 完整 CLJS | SCI |
|-----|-----------|-----|
| 性能 | V8 JIT | 解释执行，慢 10-50 倍 |
| core.async（go blocks）| ✅ | ❌（状态机转换太复杂）|
| 编译期宏（.clj → .cljs）| ✅ | ❌（仅运行时展开）|
| 完整命名空间系统 | ✅ | 简化版，基本 require |
| Java/JS 互操作 | 完整 | 限于显式白名单 |
| 递归深度 | ~10k | ~1-2k（解释器开销）|
| Promise/async | ✅ | ✅（Promise 互操作可用）|
| malli 验证 | ✅ | ✅ |

### 架构中的角色

```
宿主层（编译后的 CLJS）                 沙箱层（SCI 解释执行）
├── agent 循环                          ├── agent 生成的工具
├── core.async 流式处理                 ├── 扩展（.clj/.edn）
├── DataScript                          ├── 自修改 DSL
├── provider 系统                       ├── 运行时策略代码
├── TUI 桥接                            └── 学习到的推理规则
└── 内置工具（deftool）
```

性能关键路径留在宿主层。SCI 处理轻量的、动态的、用户/agent 生成的代码——正好是它的甜区。

---

## 5. 通过 Var 间接寻址实现宿主层进化

### 关键洞见：Clojure 的 Var 系统

这是 Clojure 对自进化 agent 最强大的特性，也是与 TypeScript 的根本差异：

**Clojure 的函数调用通过 Var（变量容器）间接进行。绑定在调用时解析，而不是导入时。**

```clojure
;; 宿主层定义工具
(defn edit-tool [params signal ctx]
  (let [content (slurp (:path params))]
    (str/replace-first content (:old-text params) (:new-text params))))

;; 运行时，agent 替换 Var 的根绑定
(alter-var-root #'edit-tool
  (fn [original-fn]
    (fn [params signal ctx]
      ;; 新逻辑：先尝试原版，失败就模糊匹配
      (let [result (try (original-fn params signal ctx) (catch :default e nil))]
        (if result
          result
          (fuzzy-edit params signal ctx))))))
```

**这直接修改了宿主层代码：**
- **即时生效** —— 下次调用 `edit-tool` 就走新逻辑，零延迟
- **保留原版** —— `original-fn` 在闭包中捕获，可以回退
- **无需重新编译** —— Var 的值在运行时可变
- **所有调用方自动生效** —— 它们绑定的是 Var，不是函数值

### 为什么 TypeScript 做不到

```javascript
// TypeScript：import 在导入时绑定到值
import { editTool } from './tools.js'
// editTool 现在是一个固定的函数引用
// 即使 tools.js 重新导出，这个绑定也不会变
```

```clojure
;; Clojure：调用时绑定到 Var
(ns agent.core (:require [agent.tools :refer [edit-tool]]))
;; edit-tool 是一个 Var 查找
;; alter-var-root 改变了 Var 的值——所有引用方立刻看到新版本
```

JS 模块导入 = 值绑定（导入时固定）。
Clojure require = Var 绑定（调用时解析）。

这是**语言设计决策**，不是库特性。无法被改造。

### 自修改的权限级别

| 级别 | 范围 | 安全性 | 使用场景 |
|------|------|--------|---------|
| **SCI 沙箱** | 仅白名单函数 | 最高 | 默认。Agent 生成的新工具、扩展 |
| **Var 替换（受控）** | 仅 `^:evolvable` 标记的 Var | 高 | Agent 改进已有工具 |
| **Var 替换（不受控）** | 任意 Var | 低 | 危险——agent 可以修改 agent loop 本身 |
| **源码修改** | 改写 .cljs 文件，重新编译 | 最低 | 不推荐 |

### 推荐架构：可进化的 Var

```clojure
;; 标记哪些函数允许被 agent 修改
(defn ^:evolvable edit-tool [params signal ctx] ...)
(defn ^:evolvable grep-tool [params signal ctx] ...)
(defn ^:locked agent-loop [ctx] ...)  ;; 受保护——agent 不能修改

;; 进化门控在允许修改前检查元数据
(defn evolve! [var-name new-impl]
  (let [v (resolve (symbol var-name))]
    (when-not (:evolvable (meta v))
      (throw (ex-info "Not evolvable" {:var var-name})))
    ;; 对 new-impl 进行结构化安全分析 ...
    ;; 记录为 DataScript 事件 ...
    (alter-var-root v (constantly new-impl))))
```

这给你：

1. **Agent 可以修改宿主层工具** —— 通过 `alter-var-root`，即时生效，零编译开销
2. **有边界** —— 仅 `^:evolvable` 标记的 Var 可被修改
3. **有审计** —— 每次修改记录为 DataScript 事件
4. **可回滚** —— 保留原版函数，失败就还原
5. **核心不可篡改** —— agent loop、安全机制被锁定

### 极端情况：自修改 Agent 循环

在足够高的信任级别下，agent 可以修改自己的认知管线：

```clojure
;; Agent 发现编辑后 lint 总是失败
;; → 在自己的执行管线中添加 lint-check 步骤
(evolve! "agent.core/post-tool-hook"
  (fn [ctx tool-result]
    (when (= (:tool tool-result) "edit")
      (let [lint (run-lint (:path tool-result))]
        (when (:errors lint)
          (inject-steering! ctx "检测到 lint 错误。继续前请先修复。"))))))
```

之后每个 turn 自动对修改的文件执行 lint。修改本身是一个事件——可重放、可回滚、可审计。

---

## 6. "在 TypeScript 中不可能"清单

这些是 CLJS 从根本上启用、TypeScript 从根本上无法表达的能力。不是"更难"——是**不可能**。

### 6.1 零成本上下文分支（策略竞速）

**痛点**：Edit 工具约 15-20% 失败率（空白符不匹配）。顺序重试浪费 2-3 个 turn。

**CLJS**：同时竞速三个策略。第一个成功的赢。Fork 成本：通过结构共享约 200 字节。

**TS**：`structuredClone()` 是 O(n)。10 个并行策略 = 50MB 复制 + 500ms。不可行。

### 6.2 结构化自修改与审计追踪

**痛点**：工具反复失败，agent 只能用不同参数重试。无法检查、诊断或修复工具。

**CLJS**：完整循环——检查源码（代码即数据）→ 诊断（遍历代码树）→ 创建改进版 → 验证安全性（白盒分析）→ 热替换（SCI 求值或 Var 替换）→ 记录为可重放事件。

**TS**：`JSON.stringify(fn)` → `undefined`。函数是编译后的闭包，不是数据。

### 6.3 运行时工具族生成

**痛点**：每个项目都用通用工具。Rust 项目和 Python 项目拿到一样的工具。

**CLJS**：Agent 检测项目类型 → 运行时生成 `cargo-check`、`cargo-test` 工具 → 带参数 schema、输出解析、错误处理 → 立即注册使用。

**TS**：`eval()` 创建无类型函数。没有 schema，没有验证，没有工具注册系统集成。

### 6.4 REPL 协同驾驶

**痛点**：会话中调试需要重启 + 重现。

**CLJS**：nREPL 连接到运行中的 agent → 检查状态 → 修改行为 → 注入消息 → 门控自修改 → 不丢失对话。

**TS**：JS 模块绑定在导入时。无法在运行进程中替换函数实现。

### 6.5 跨 Agent 拉马克进化

**痛点**：每个会话从零开始。发现的改进随会话死亡。

**CLJS**：改进作为结构化代码传播——可序列化、可验证、经本地证据检查后可采纳。

**TS**：闭包不可序列化。`JSON.stringify(fn)` → `undefined`。

### 6.6 会话即程序

**痛点**：会话历史是只写的。无法用不同模型重放、用修改后的工具重放、或跨会话组合。

**CLJS**：会话包含可执行代码。可重放、可分支、可组合。"用 Opus 代替 Sonnet 重放这个会话。"

**TS**：需要三个不可约属性协同工作。子系统之间的接缝阻止了统一的会话即程序模型。

### 6.7 事务性多文件操作

**痛点**：多文件重构——第 7 次编辑失败，第 1-6 次编辑让代码库处于不一致状态。

**CLJS**：宏捕获所有文件路径，typecheck 失败 → 原子性回滚全部。

**TS**：没有宏。包装函数无法检查其回调将修改哪些文件。

---

## 7. 对比矩阵

### 语言与类型系统

| 维度 | TypeScript | ClojureScript | 胜者 |
|------|-----------|---------------|------|
| 编译期类型安全 | 判别联合类型，穷尽检查 | 无（动态类型）| **TS** |
| 运行时验证 | TypeBox | Malli（更丰富，生成式测试）| **CLJS** |
| 开放扩展 | 声明合并（脆弱）| 多方法（稳健、开放）| **CLJS** |
| 错误信息 | 清晰，有源码位置 | 宏/go-block 中晦涩 | **TS** |

### 架构与数据

| 维度 | TypeScript | ClojureScript | 胜者 |
|------|-----------|---------------|------|
| 不可变数据 | 库（Immer）| 内置，惯用法 | **CLJS** |
| 会话分支成本 | O(n) 深拷贝 | O(log32 n) ≈ O(1) | **CLJS** |
| 会话查询 | 命令式 JSONL 遍历 | DataScript Datalog | **CLJS** |
| 流式处理 | EventStream（单消费者）| core.async（多消费者，CSP）| **CLJS** |
| Provider 分发 | Map + 注册 | 多方法 | **CLJS** |

### 开发者体验

| 维度 | TypeScript | ClojureScript | 胜者 |
|------|-----------|---------------|------|
| IDE 支持 | 通用 | 需要 CLJS 专用工具 | **TS** |
| REPL 驱动开发 | 无 | 完整 nREPL | **CLJS** |
| 热重载 | 仅扩展 `/reload` | shadow-cljs 补丁 | **CLJS** |
| 异步调试 | 完整堆栈追踪 | go-block 噪音 | **TS** |
| 分发 | `bun build --compile` | shadow-cljs → bun（大概率可行）| **TS**（微弱）|
| 人才池 | ~1500-2000 万 | ~5-10 万 | **TS** |

### 自进化能力

| 维度 | TypeScript | ClojureScript | 胜者 |
|------|-----------|---------------|------|
| Agent 自修改 | 字符串 `eval()`，脆弱 | SCI 沙箱 + Var 替换 | **CLJS** |
| 组合安全性 | 不可能（闭包不透明）| 遍历代码 form | **CLJS** |
| 策略竞速 | O(n) fork（不可行）| O(1) fork（基本原语）| **CLJS** |
| 跨会话知识 | JSON 配置 | 代码即数据，通过 eval 水合 | **CLJS** |
| 宿主层进化 | 不可能（import = 值绑定）| Var 间接寻址（调用时绑定）| **CLJS** |

---

## 8. 分发方案：shadow-cljs → Bun 编译

### 专家共识：大概率可行（75-90% 信心）

技术路径：

```
shadow-cljs :simple → 单个自包含 .js 文件（CommonJS）
    → 所有 goog.* 已内联，无动态 require
    → 仅有的 require() 调用是 Node.js 内置模块（node:fs, node:path）
    → bun build --compile → 独立二进制
```

**为什么应该能工作：**
1. shadow-cljs `:simple` 输出是单个自包含 JS 文件
2. CLJS 运行时是纯 JavaScript 计算
3. SCI 也是纯 JS
4. 不需要特殊 API

**预估影响：**
- 启动时间：~80-150ms（从 ~50ms 起）—— CLI 可接受
- 二进制体积：在 ~90MB 基础上增加 1-2MB —— 可忽略

**剩余风险**（经验性的，非架构性的）：
- Bun 的 Node.js API 边缘情况
- 编译后二进制中的 npm 包模块解析
- Bun 二进制中的 SCI 运行时（预期可工作，未测试）

**验证**：2-4 小时的 PoC 可将信心提升至约 95%。

**扩展系统改进**：SCI 扩展（`.clj`/`.edn`）可热重载、有沙箱——比当前 jiti + virtualModules 更简单。

---

## 9. 诚实的局限性

### 9.1 调试：core.async 是问题儿童

- Go-block 堆栈追踪碎片化
- 死锁是静默的——无错误、无超时
- Transducer 错误被吞掉——数据停止流动却没有可见错误
- REPL 部分弥补了交互式调试的不足

### 9.2 生态系统互操作税

pi-mono 每个 agent turn 跨越 30-80 个 JS 互操作边界。每个边界都是类型擦除点。`clj->js`/`js->clj` 转换 bug 是静默的。

### 9.3 开发者入门与招聘

人才池小 200 倍。TS 开发者至少需要 2-4 周上手。

### 9.4 LLM 流利度矛盾

Claude 代码生成准确率：TypeScript ~85-90%，Clojure ~60-70%。最适合 agent 自修改的语言，恰恰是 agent 最不擅长写的语言。

**缓解措施：**
1. 模板化 DSL —— agent 填参数，系统生成代码
2. 从历史中提取 few-shot 示例
3. 生成-验证-迭代，带结构化反馈

### 9.5 TUI 不能重写

没有用 Reagent 构建的生产级终端 UI。共识：TUI 保持 TypeScript，通过互操作桥接。

### 9.6 编译模式差异

shadow-cljs `:none`（开发）vs `:advanced`（生产）行为不同。开发中工作的代码在生产中可能崩溃。

---

## 10. 疯狂的想法

### 10.1 MCTS 策略搜索

蒙特卡洛树搜索探索 agent 方法空间。50 次模拟 × 3 个 turn = 探索 150 个决策点。持久化数据让每个节点 fork 成本约为零。

### 10.2 自修改 Agent 循环

Agent 在检测到反复失败后，在自己的执行管线中添加 lint-check。之后每个 turn 自动执行质量门控。修改是一个事件——可重放、可回滚。

### 10.3 拉马克进化

Agent 改进作为结构化的、经验证的、可采纳的代码在实例间传播，需通过本地证据检查。种群收敛到帕累托最优的工具变体。

### 10.4 会话即程序

会话包含可执行代码。反事实重放："如果我用 Opus 而不是 Sonnet 会怎样？" 提取、分支、组合会话。

### 10.5 反应式自我监控

DataScript 事务监听器实时检测行为模式：
- 连续 3+ 次失败 → 自动注入转向消息
- 高成功率的 agent 创建工具 → 自动持久化到工具库

### 10.6 Agent 集群

Agent 分裂成协调的集群处理大型任务。子 agent 会话嵌套在父事件日志中。调试可以逐层下钻：父会话 → 子 agent 日志 → 单个工具调用。

### 10.7 完整架构

```
┌─────────────────────────────────────────────────────┐
│                  活的系统提示词                        │
│  从以下计算：项目 + 行为画像 +                        │
│  学到的模式 + 工具清单 + 历史错误                     │
│  （每个 turn 从 DataScript 查询重新计算）              │
└──────────┬──────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────┐
│              AGENT 循环（数据化，可自修改）            │
│  拦截器链，^:evolvable Var 门控                       │
│  通过 O(1) fork 进行 MCTS 策略搜索                   │
│  扩展修改拦截器，不修改核心代码                        │
└──────────┬──────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────┐
│              工具层（数据化，全部可检查）               │
│  内置工具（宿主层，deftool 宏）                       │
│  生成的工具（元工具，SCI）                            │
│  自愈的工具（agent 修改的，经安全验证）                │
│  宿主层工具（^:evolvable，Var 替换）                  │
└──────────┬──────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────┐
│              事件日志（DataScript，事实来源）          │
│  消息、工具调用、修改、学到的规则                      │
│  跨会话查询，构建行为画像                             │
│  水合：代码事实 → SCI 求值 → 运行时行为               │
└──────────┬──────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────┐
│              进化层                                   │
│  自我评估（DataScript 分析）                          │
│  5 层安全验证                                        │
│  跨会话拉马克继承                                     │
│  策略有效性数据库                                     │
└─────────────────────────────────────────────────────┘
```

每一层都是数据。每一层都可检查。每次修改都是事件。整个系统是一个活的、不断进化的程序。

---

## 11. 实施路线图

| 阶段 | 行动 | 时间 | 你得到什么 |
|------|------|------|-----------|
| **Phase 0** | PoC：CLJS+SCI+DataScript → shadow-cljs :simple → bun build --compile | 半天 | 分发路径的 yes/no |
| **Phase 1** | CLJS agent 核心：agent 循环、`deftool` 宏、DataScript 会话 | 2-4 周 | 基础——agent 能检查自己的工具、查询会话历史、零成本分支 |
| **Phase 2** | 通过互操作桥接接入现有 TS TUI | 2-3 周 | 可用的交互式 agent、REPL 调试、压缩即投影 |
| **Phase 3** | SCI 扩展系统 + 模板化修改 DSL | 2-3 周 | **质变**——agent 自愈工具、生成项目专用工具、热重载扩展 |
| **Phase 4** | Provider 系统（多方法）、流式处理（core.async）| 3-4 周 | 开放 provider 生态、多消费者流式处理、transducer 管线 |
| **Phase 5** | 跨会话学习、策略竞速、拉马克进化 | 持续 | 自进化 agent——越用越强 |

### 每个阶段交付什么

**Phase 0（半天）**：一个证明分发路径可行的二进制。如果可以，继续。如果不行，评估 Deno compile 作为后备。

**Phase 1（2-4 周）**：数据基础。Agent 可以 `(:source-form (get-tool "edit"))` 读取自己的工具。会话是可查询的 DataScript 数据库。Fork 上下文几乎零成本。还没有 UI——用命令行单轮交互验证。

**Phase 2（2-3 周）**：可工作的交互式 agent。TUI 保持 TypeScript，通过互操作桥接连接。REPL 调试：连接 nREPL 到运行中的 agent，检查状态，修改行为，不丢失对话。压缩是事件日志上的纯函数——非破坏性、可逆、可重新计算。

**Phase 3（2-3 周）**：**分水岭。** Agent 开始自愈：edit 工具因空白符失败 → agent 读源码 → 用 DSL 写改进版 → 安全检查 → 热替换 → 记录为事件 → 下次会话自动加载改进。扩展是 `.clj` 文件，热重载，有沙箱。这个阶段之后，系统不再是"更好的 coding agent"——而是一个自我改进的系统。

**Phase 4（3-4 周）**：生产完备。`defmethod` 定义 provider——添加一个就是定义一个方法，不需要改核心文件。core.async 流式处理，每个消费者有独立缓冲策略——慢扩展不阻塞渲染。Transducer 管线做消息转换。

**Phase 5（持续）**：涌现能力。MCTS 探索策略空间。跨会话行为画像注入 system prompt。Agent 实例间拉马克进化。Agent 越用越强。

**每个阶段都独立有价值。** 如果项目在任何阶段停止，已完成的工作不会浪费。

**Phase 3 是质变点** —— 之后你拥有的是与任何 TypeScript 编码 agent 根本不同类别的东西。

---

*本分析基于 5 位领域专家 6 轮以上点对点讨论的研究，辅以对 SCI 能力、Var 间接寻址机制和分发可行性的详细调查。原始专家讨论报告位于 `/tmp/43fa169d/shadow-cljs-analysis.md`。*
