# FXML 2.0 IntelliJ Plugin

An IntelliJ IDEA plugin that provides IDE support for [FXML 2.0](https://github.com/jfxcore/fxml-compiler) files used by the JFXcore FXML compiler.

A `.fxml` file is treated as FXML 2.0 when its root element carries both:
- `xmlns="http://javafx.com/javafx"`
- `xmlns:fx="http://jfxcore.org/fxml/2.0"`

### Features

#### Standalone FXML 2.0 files

- Dedicated FXML 2.0 file type with XML syntax highlighting, folding, and formatting
- Tag and attribute resolution, code completion, and navigation to JavaFX classes
- Find usages, rename, and Go to Declaration for `fx:id` identifiers
- Inspections for unknown tags, unresolved attributes, unused imports, and more
- FXML-aware import optimizer

#### Embedded FXML 2.0 via `@ComponentView`

FXML 2.0 markup can be embedded directly in a Java or Kotlin class using the
`@ComponentView` annotation.
The plugin injects full FXML 2.0 IDE support into the annotation value:

- Language injection: the embedded markup is treated as a live FXML 2.0 document with the
  same completion, navigation, inspections, and find usages as standalone files
- Formatting: `Ctrl+Alt+L` reformats the embedded XML with the correct indentation,
  honoring EditorConfig `*.fxml` rules
- Unused-import suppression: imports referenced only inside the embedded markup are not
  reported as unused by Java / Kotlin inspections
- Embed markup intention / inspection: converts a standalone `.fxml` file into a
  `@ComponentView` annotation in the code-behind class (`Alt+Enter` on the root element or
  `fx:subclass` attribute)
- Create FXML file intention / inspection: extracts the embedded markup back to a
  standalone `.fxml` file, properly formatted and with all `<?import?>` PIs restored
  (`Alt+Enter` on the `@ComponentView` annotation name)

---

## Requirements

- **IntelliJ IDEA 2025.2** or newer (Community or Ultimate)

---

## Building

Clone the repository and run the Gradle `buildPlugin` task:

```bash
./gradlew buildPlugin
```

The packaged plugin ZIP will be created at:

```
build/distributions/fxml-intellij-plugin-<version>.zip
```

---

## Installing

### Option A: Install from disk (permanent installation)

1. Open **IntelliJ IDEA**
2. Go to **File → Settings** (`Ctrl+Alt+S`) → **Plugins**
3. Click the **gear icon** (⚙) at the top of the Plugins panel
4. Select **Install Plugin from Disk...**
5. Navigate to `build/distributions/` and select the generated `.zip` file
6. Click **OK**, then **Restart IDE** when prompted

### Option B: Run in a sandboxed IDE (development / testing)

Launch a disposable IntelliJ instance with the plugin already loaded, without touching your main installation:

```bash
./gradlew runIde
```

---

## Running Tests

```bash
./gradlew test
```

Test reports are written to `build/reports/tests/test/`.

---

## AI-Generated Code Disclosure

A significant portion of the source code in this repository was written with the assistance of large language models
(LLMs). Contributions are reviewed and integrated by the project maintainers.
