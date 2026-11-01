<h1 align="center">Patchright Java</h1>

<p align="center">
  Undetected Java version of the Playwright testing and automation library.
</p>

> **Note**
> This repository serves the Patchright Java Package. To use Patchright with Python or NodeJS, check out the [Python Package](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright-python) or the [NodeJS Package](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright-nodejs). Also check out the main [Patchright Driver Repository](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright).

## Installation

### JitPack

JitPack builds the artifacts straight from this repository and serves them without authentication.

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.mar-bros.patchright-java</groupId>
  <artifactId>patchright</artifactId>
  <version>TAG</version>
</dependency>
```

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.mar-bros.patchright-java:patchright:TAG'
}
```

`TAG` is a release tag such as `v1.64.0` (its numbers pick the Playwright version), a commit hash or `main-SNAPSHOT` (these build the version pinned by the `playwright-java` submodule). The first request of a version triggers a build of several minutes, so Gradle may need longer timeouts in `gradle.properties`:

```properties
systemProp.org.gradle.internal.http.connectionTimeout=180000
systemProp.org.gradle.internal.http.socketTimeout=180000
```

### GitHub Packages

Add to your `pom.xml`:

```xml
<dependency>
  <groupId>cc.marbro.patchright</groupId>
  <artifactId>patchright</artifactId>
  <version>LATEST_VERSION</version>
</dependency>
```

Since this is published to GitHub Packages, you'll also need to configure the repository. GitHub Packages rejects anonymous downloads, so Maven needs a token with the `read:packages` scope for the `github` server in `settings.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/mar-bros/patchright-java</url>
  </repository>
</repositories>
```

## Usage

Patchright is a **drop-in replacement** for Playwright. Since only the Maven coordinates (`groupId` and `artifactId`) were modified and the internal Java package names remain unchanged, **no code adjustments are necessary**. Just keep using the standard Playwright imports:

```java
import com.microsoft.playwright.*;
```

> **Warning**
> Patchright only patches CHROMIUM based browsers. Firefox and Webkit are not supported.

### Recommended Configuration

```java
try (Playwright playwright = Playwright.create()) {
    Browser browser = playwright.chromium().launch(
        new BrowserType.LaunchOptions()
            .setChannel("chrome")
            .setHeadless(false)
    );
    BrowserContext context = browser.newContext(
        new Browser.NewContextOptions()
            .setViewportSize(null) // No default viewport
    );
    Page page = context.newPage();
    page.navigate("https://example.com");
}
```

> **Note**
> We recommend using Google Chrome instead of Chromium. Install it via `playwright install chrome` and use it with `.setChannel("chrome")`.

## Documentation and API Reference

Patchright is a drop-in replacement for Playwright-Java. It includes several API additions to control stealth behavior and enhanced selector capabilities.

### `isolatedContext` (boolean)

- **Default**: `true`
- **Supported Methods**: `evaluate` and `evaluateHandle` across `Page`, `Frame`, `JSHandle`, `Worker`, and `Locator`.

When `isolatedContext` is set to `true` (default), scripts are executed in an **isolated utility context** (Utility World). This prevents the detectable `Runtime.enable` feature from being activated in the main world, bypassing a major automation detection point.

```java
// Stealth Execution (Utility World) - Default
page.evaluate("() => document.title"); 

// Standard Execution (Main World) - No Patch
page.evaluate("() => document.title", null, false);
```

### `focusControl` (boolean)

- **Default**: `false`
- **Supported Options**: `BrowserType.LaunchOptions`, `BrowserType.LaunchPersistentContextOptions`, `Browser.NewContextOptions`, `Browser.NewPageOptions`.

Controls browser focus management to prevent focus-based automation detection.

```java
BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
    .setFocusControl(true);
Browser browser = playwright.chromium().launch(options);
```

### Closed Shadow Roots

Patchright natively supports interacting with elements inside **Closed Shadow Roots** without extra configuration.

*   **Locators**: Standard locators automatically pierce through closed shadow DOMs.
*   **XPaths**: Patchright enables XPath engine support for traversing into closed shadow roots.

---

## How It Works

### [Runtime.enable](https://vanilla.aslushnikov.com/?Runtime.enable) Leak
Patchright avoids using `Runtime.enable` by executing JavaScript in isolated ExecutionContexts, which is the biggest patch it applies.

### [Console.enable](https://vanilla.aslushnikov.com/?Console.enable) Leak
Patchright disables the Console API to prevent this leak. Console functionality will not work.

### Command Flags Leaks
Patchright tweaks the default browser args to remove obvious automation fingerprints:
- `--disable-blink-features=AutomationControlled` (added)
- `--enable-automation` (removed)
- `--disable-popup-blocking` (removed)
- `--disable-component-update` (removed)
- `--disable-default-apps` (removed)
- `--disable-extensions` (removed)

### General Leaks
Patchright patches various general leaks within the Playwright codebase, addressing suboptimal setups and common detection points that standard automation tools often miss.

## Stealth

With the right setup, Patchright is considered undetectable and passes:
- [Brotector](https://ttlns.github.io/brotector/) ✅
- [Cloudflare](https://cloudflare.com/) ✅
- [Kasada](https://www.kasada.io/) ✅
- [Akamai](https://www.akamai.com/products/bot-manager/) ✅
- [Datadome](https://datadome.co/products/bot-protection/) ✅
- [Fingerprint.com](https://fingerprint.com/products/bot-detection/) ✅

## Development

This project uses a JavaParser-based AST patcher to transform the official [playwright-java](https://github.com/microsoft/playwright-java) source code into patchright-java. 

**Official Package Requirements Compliance**  
This repository fully complies with the [official community-driven guidelines](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright/issues/89) set by the core maintainer:
- ✅ **Automatic CD**: Built natively with AST-based patching.
- ✅ **Extended API Support**: Full support for `isolatedContext` and `focusControl`.
- ✅ **InitScript Routing**: Intercepts document requests via internal fallback logic to securely run stealth scripts before pages load.

### Building from source

Requires Bash, Git, a JDK, Maven, `curl` or `wget`, `unzip`, `tar` and, for Playwright 1.63 and newer, Node.js with `npm` (the driver is assembled from npm packages).

```bash
# Build with specific version
bash scripts/build.sh 1.52.0

# Build with latest
bash scripts/build.sh
```

### Project Structure

```text
patchright-java/
├── .github/                  # CI/CD workflows and release automation
├── patchright-java-patcher/   # JavaParser-based AST patching tool (The Core)
├── patchright-java-release/   # Target directory for patched source and built artifacts
├── patchright-java-test/      # Integration test module for stealth verification
├── playwright-java/           # Submodule tracking official microsoft/playwright-java
├── scripts/                   # Build, test, and download automation scripts
├── jitpack.yml                # JitPack build entry (runs scripts/jitpack.sh)
├── pom.xml                    # Root Maven Project (Multi-module)
├── README.md
└── LICENSE
```

## Copyright and License

© [Vinyzu](https://github.com/Vinyzu/)

Patchright is licensed [Apache 2.0](https://choosealicense.com/licenses/apache-2.0/)

## Disclaimer

This repository is provided for educational purposes only. No warranties are provided regarding accuracy, completeness, or suitability for any purpose. Use at your own risk.
