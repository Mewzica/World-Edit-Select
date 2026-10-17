# World Edit Select (WES)

A client-side Minecraft Fabric mod that turns `//set` into a visual block picker — right inside the chat box.

Instead of memorizing block IDs and percentage syntax, type `//set ` in chat and a picker opens above the input line. Click the blocks you want, assign percentages (or leave them blank to auto-balance), and WES writes a ready-to-send WorldEdit command back into your chat input.

> `wes` is the mod id; **World Edit Select** is the full name.

## Features

- **Trigger by typing** — the picker opens the moment the chat input is exactly `//set ` (trailing space included). No keybind, no command, no extra UI.
- **Stays in chat** — the panel is an overlay attached to the chat screen, not a separate screen. The chat history and the input line stay visible, and you can still see what people are saying.
- **Searchable block list** — a live search field filters by localized name or block ID. Only the rows inside the viewport are rendered, so the ~1000-entry list costs nothing.
- **Percentage picking** — each selected block gets a percentage field. Leave it empty and the remaining percentage is distributed evenly across the empty entries.
- **Block states** — blocks with properties (beds, stairs, logs, slabs, …) get a `St` button that opens a property editor with `<` / `>` cycling. Only non-default properties are written into the command, e.g. `minecraft:blue_bed[part=head]`.
- **Never sends anything on its own** — the generated `//set ...` is written back into the chat input. Review it, edit it, then press Enter yourself.
- **Client side only** — no server installation required, nothing is registered on the server, and it works on any server where you already have WorldEdit permissions.

## Usage

1. Open chat and type `//set ` — note the trailing space.
2. The picker opens above the input line and focuses the search box.
3. Click blocks on the left to add them; click again (or click the row on the right) to remove them.
4. Optionally set percentages, and use the `St` button to pick block states.
5. Press **Confirm** — the command is written into the chat input.
6. Press **Enter** to send it.

| Key / Action | Result |
| --- | --- |
| `Enter` | Confirm and write the command into the input |
| `Esc` | Close the picker (chat stays open); in the state editor, go back |
| Scroll | Scroll the block list / selected list |
| Click outside the panel | Close the picker |

## Requirements

| | |
| --- | --- |
| Minecraft | 1.20.1 |
| Loader | Fabric Loader 0.14+ |
| Dependency | [WorldEdit](https://modrinth.com/mod/worldedit) 7.2+ (Fabric), client side |
| Java | 17 |

## Installation

1. Install Fabric Loader for Minecraft 1.20.1.
2. Drop `worldedit-fabric` and `wes-<version>+mc1.20.1.jar` into `.minecraft/mods`.
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

## License

MIT — see [LICENSE](LICENSE).
