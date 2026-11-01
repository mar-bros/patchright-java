# Patchright Java Patcher

`patchright-java-patcher` 是 Patchright Java 的核心代码生成工具，它的主要职责是在每次上游 `playwright-java` 发布新版本时，自动对官方原始源码进行“特征改造”，编译出一套原生感知不到的 Stealth（隐形）环境变体。

## 1. 改造原理与内容

本工具**并非依赖易错的、基于字符串层面的正则文本替换，而是基于 [JavaParser](https://javaparser.org/) 进行抽象语法树（AST）级别的解析与重构**。它会将原始的 `.java` 代码解析为结构化语法树节点，然后在节点层面上精准定位到特定的类、接口、方法签名和参数进行静态代码手术，最后重新渲染生成新的 `.java` 源码。

具体应用的代码 Patch（改造）内容主要包含如下六类：

- **Driver Patch（驱动替换）**：拦截源码底层的 Driver 下载逻辑，将其下载 URL 指针从官方 Playwright 驱动切换为包含无头防侦测补丁的开源 **Patchright Driver** 构建产物（旧版下载脚本改写为 Patchright Release 的 zip 地址；1.63 起上游改用 `npm pack playwright-core`，则改为打包 npm 上的 `patchright-core`）。
- **Script Injection Patch（脚本特征清理）**：移除 Playwright 在注入 JavaScript 代码时默认携带的 `sourceURL` 和其他调试注释尾巴。这阻止了反爬机制通过跟踪代码来源或浏览栈信息探知自动化脚本的存在。
- **Isolated Context Patch（隔离执行环境）**：这是对抗检测最核心的改造。工具为 `Frame`、`JSHandle`、`Page`、`Locator` 等核心 API 的所有 `evaluate` 及 `evaluateHandle` 系列方法自动注入了名为 `isolatedContext` 的新参。当该参数生效（默认开启）时，底层的 JS 调用的 `world` 环境会从 `'main'` 强制切换至 `'utility'`。这使得业务侧的 JS 操作都在浏览器隔离的沙盒节点中执行，**彻底规避了最易暴露的 `Runtime.enable` 特征检测点**。
- **Route Injection Patch（路由注入）**：通过接管底层请求，安装并隐式绑定拦截路由，实现在每个 Document 请求建立前，静默执行一系列环境欺骗和特征抹除的 InitScript。
- **Focus Control Patch（焦点管理）**：扩充了底层 Browser 及 Context 的 Launch Options，新增 `focusControl` 支持从而能够突破或者接管特定环境下的焦点监听限制。
- **Metadata Patch（POM 元数据平移）**：把构建配置文件 `pom.xml` 内的全部构建坐标属性从官方微软版的 `com.microsoft.playwright:playwright` 自动等价替换为当前工程私有的 `cc.marbro.patchright:patchright`。

## 2. 如何保证全面且与官方 Patchright 对齐不遗漏？

Patchright Java 的核心适配目标是 1:1 对标 `patchright`（Node.js / Python 原作）的全部 Stealth 特性。为了确保这套适配机制不仅在当下完整，且在未来面临上游迭代时仍然健壮，我们采用了多套对齐与防漏策略：

### 2.1 对齐策略：底层复用与 Binding 层分离

如果原版的 Playwright-Java 或主线 Patchright 发生特征演进，这里的适配和调整成本非常低：
1. **Driver 级特性的自动继承**：Playwright-Java 在底层其实是通过启动内部捆绑的 Node.js Driver 来进行真实通信的。由于 `DriverPatch` 会将下载源直接指向**已经彻底改造好**的 `Patchright NodeJS Driver` 产物，因此 **90% 的底层隐形机制（如 Chrome UA 伪装、Headless 模式绕过、核心协议拦截等）不需要在 Java 测重写**，只要升级 Driver 版本即可直接享用最新的防检测成果。
2. **Binding 级快速微调**：仅限于停留在 Java 接口层面（Binding 层）的漏洞（如 `isolatedContext` 环境隔离、代码 `sourceURL` 等注入标识），才需要依赖上述的 AST Patch 进行外科手术。如果上游接口大改，开发者只需在 `cc.marbro.patchright.patcher.patches` 目录下修改或追加轻量级的特征替换规则节点（Node Finder），即可快速完成逻辑跟随。

### 2.2 防漏检查：从脚本断言到强类型校验的升维

在 Python 版本的实现（如 [`patch_python_package.py`](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright-python/blob/main/patch_python_package.py)）中，代码主要依靠预设目标数组的匹配断言（Assert 替换命中次数）来检查修改是否发生了遗漏。Java 版的 Patcher 一方面沿用了类似的监控防漏思想，另一方面由于 Java 的天然属性获得了更严谨的防漏墙：

1. **AST 结构化捕获命中率检查**：Patcher 会使用 `JavaParser` 在全局范围内扫描特定“接口契约”（如所有的 `evaluate`、`evalOnSelector` 声明）。如果上游 `playwright-java` 的内部架构发生破坏性变更，使得 Patcher 无法全量匹配预设的 AST 节点或文本替换未命中任何内容，Patcher 会直接抛出 `IllegalStateException`（注明文件与目标）并以非 0 退出码终止构建，避免发布未隐身的产物；已打过补丁的目标会被识别并跳过，因此重复运行是安全的。
2. **动态向下的继承与重载自适应（Delegation Coverage）**：无论内部新增多少个诸如 `LocatorImpl` 的新增代理类，由于工具不仅仅抓取文本，而是抓取整个类图和继承结构，能一次性顺着继承和委托关系将其上下游涉及的参数全部自适应更新完毕。
3. **Java 编译期强约束（终极防线）**：Python 依赖运行时的补丁完整性检查，而本工具对 `.java` 修改完毕后，会立即交由 CI 上的原版 `mvn compile` 进行全量编译。由于 Java 是极高约束的静态语言，如果此过程中遗漏了任何一个实现类的 `isolatedContext` 同步，或者匹配不完整，**编译器会在打包阶段直接报错抛出 `Method parameters mismatch` 或无法实现接口的致命异常**。这完全消灭了带病上线的可能，成为保证 Patch “没有死角”的最强屏障。
