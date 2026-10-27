# World Edit Select (WES)

A client-side Minecraft Fabric mod that turns WorldEdit's block commands into a visual block picker — right inside the chat box.

Instead of memorizing block IDs and percentage syntax, type `//set ` in chat and a picker opens above the input line. Click the blocks you want, assign percentages (or leave them blank to auto-balance), and WES writes a ready-to-send WorldEdit command back into your chat input.

It is not limited to `//set`. Every WorldEdit command that takes a block pattern is supported, and the panel shows the extra arguments each one needs — thickness, radius, height, size, or the mask to replace.

> `wes` is the mod id; **World Edit Select** is the full name; **创世神选块** is the Chinese name.

## Features

- **Toggle switch** — a small switch sits just above the chat input. Leave it on and typing a command prefix opens the picker; turn it off and the chat box stays vanilla, so you can type `//set ` by hand without the panel jumping in.
- **Trigger by typing** — the picker opens the moment the chat input is exactly a command prefix plus a space (`//set `, `//replace `, `//sphere `, …). No keybind, no command, no extra UI.
- **Command-aware arguments** — the panel follows each command's real signature, so the fields appear in the right order: `//hollow <thickness> <pattern>` puts thickness *before* the pattern, `//cyl <pattern> <radius> <height>` puts it *after*, and `//replace <from> <pattern>` adds a "block to replace" picker.
- **Stays in chat** — the panel is an overlay attached to the chat screen, not a separate screen. The chat history and the input line stay visible, and you can still see what people are saying.
- **Searchable block list** — a live search field filters by localized name or block ID. Only the rows inside the viewport are rendered, so the ~1000-entry list costs nothing.
- **Percentage picking** — each selected block gets a percentage field. Leave it empty and the remaining percentage is distributed evenly across the empty entries.
- **Block states** — blocks with properties (beds, stairs, logs, slabs, …) get a `St` button that opens a property editor with `<` / `>` cycling. Only non-default properties are written into the command, e.g. `blue_bed[part=head]`.
- **Short IDs** — vanilla blocks are written without the `minecraft:` prefix (`//set stone`, not `//set minecraft:stone`), because WorldEdit fills in the default namespace anyway. Modded blocks keep theirs (`create:cogwheel`). Chat commands cap at 256 characters, so the saved prefix is real budget.
- **Never sends anything on its own** — the generated command is written back into the chat input. Review it, edit it, then press Enter yourself.
- **Client side only** — no server installation required, nothing is registered on the server, and it works on any server where you already have WorldEdit permissions.

## Supported commands

| Command | Generated form | Extra input in the panel |
| --- | --- | --- |
| `//set` | `//set <pattern>` | — |
| `//replace` | `//replace <from> <pattern>` | block to replace |
| `//overlay` | `//overlay <pattern>` | — |
| `//center` | `//center <pattern>` | — |
| `//walls` | `//walls <pattern>` | — |
| `//faces` | `//faces <pattern>` | — |
| `//line` | `//line <pattern> <thickness>` | thickness |
| `//curve` | `//curve <pattern> <thickness>` | thickness |
| `//hollow` | `//hollow <thickness> <pattern>` | thickness (written *before* the pattern) |
| `//sphere` / `//hsphere` | `//sphere <pattern> <radius>` | radius (accepts ellipsoid lists like `3,5,7`) |
| `//cyl` / `//hcyl` | `//cyl <pattern> <radius> <height>` | radius, height |
| `//pyramid` / `//hpyramid` | `//pyramid <pattern> <size>` | size |

`//forest` and `//flora` are intentionally absent: their signatures take a density, not a block pattern, so a block picker would produce an invalid command.

## Usage

1. Open chat and type one of the prefixes above, e.g. `//set ` — note the trailing space.
2. The picker opens above the input line, shows the command name in the top-right corner, and focuses the search box.
3. Click blocks on the left to add them; click again (or click the row on the right) to remove them.
4. Optionally set percentages, and use the `St` button to pick block states.
5. Fill in the command-specific fields (thickness / radius / height / size), or pick the block to replace with `//replace`.
6. Press **Confirm** — the command is written into the chat input.
7. Press **Enter** to send it.

| Key / Action | Result |
| --- | --- |
| `Enter` | Confirm and write the command into the input |
| `Esc` | Close the picker (chat stays open); in the state editor, go back |
| Scroll | Scroll the block list / selected list |
| Click outside the panel | Close the picker |

## Requirements

| | |
| --- | --- |
| Minecraft | 1.20.4 |
| Loader | Fabric Loader 0.14+ |
| Dependency | [WorldEdit](https://modrinth.com/mod/worldedit) 7.2+ (Fabric), client side |
| Java | 17 |

## Installation

1. Install Fabric Loader for Minecraft 1.20.4.
2. Drop `worldedit-fabric` and `wes-<version>+mc1.20.4.jar` into `.minecraft/mods`.
3. Launch the game. No configuration needed.

## Building

```bash
./gradlew build
```

The remapped jar is written to `build/libs/`.

The project compiles against `com.sk89q.worldedit:worldedit-core` (compile-only). WorldEdit is **not** bundled — the classes are provided at runtime by the WorldEdit mod you install.

## How it works

- `TextFieldWidgetMixin` hooks `TextFieldWidget#onChanged` — the single funnel that keyboard input, pasting and IME commits all go through (note that `write` bypasses `setText` entirely, so hooking `setText` would miss typing).
- `ChatScreenMixin` forwards render / mouse / scroll / key events to the panel and draws it at the tail of `ChatScreen#render`.
- The panel's widgets are **not** registered as screen children; focus is handed over with `ParentElement#setFocus`, which lets the inherited `charTyped` default route characters into the field being edited.
- Blocks are collected from WorldEdit's `BlockType.REGISTRY` when available, falling back to the vanilla `Registries.BLOCK` (WorldEdit only populates its registry when a server platform boots, which never happens on a remote-server client).
- The command table lives in `BlockCommand` and was written against the real signatures in `RegionCommands` / `GenerationCommands` (verified with `javap`), which is why `//hollow` takes its thickness first while `//cyl` takes it last.
- A single selected block is emitted as a bare ID. WorldEdit's `RandomPatternParser` starts with `if (patterns.size() == 1) return null;`, so `100%stone` is parsed as a block *name* and fails — percentages only work with two or more entries.

## Compatibility

The panel is drawn from `ChatScreen#render`, which is enough for vanilla chat. Chat mods that replace the chat screen entirely and deliberately skip `super.render` — AtomChat is one — would otherwise get an invisible panel, so WES ships a second, optional mixin for `AtomChatScreen`.

That mixin is best-effort by design: its injections use `require = 0`, so if AtomChat renames those methods the panel simply stops appearing instead of crashing the game. It targets the intermediary names (`method_25394`, …) because a mod class's overrides of Minecraft methods get renamed on remap, and a `targets = "..."` mixin gets no refmap entry to translate `render` for us.

## License

MIT — see [LICENSE](LICENSE).
