# Axiom Blueprint Items

A Kotlin/Fabric addon that puts saved Axiom blueprints in the creative inventory.
Blueprint items are reusable links to files in your Axiom library.

## Requirements

- Minecraft Java Edition 26.2
- Java 25
- Fabric Loader 0.19.5 or later
- Fabric API 0.161.0+26.2
- Fabric Language Kotlin 1.14.2+kotlin.2.4.21 or later
- Axiom 6.1.3

Supports singleplayer creative mode. Multiplayer and Bedrock are not supported.

## Installation

Put the addon jar and its dependencies in your Minecraft instance's `mods` folder.
Save blueprints through Axiom as usual, then open the creative inventory and
select **Blueprints**. The tab uses the free bottom-row position before Inventory
when available; otherwise, find it with Fabric's page arrows.

Search by name or select tags. Multiple selected tags must all match.
**Refresh** rereads the library, including nested folders. The filter panel uses
Minecraft GUI textures, so resource packs can change its appearance.

## Placement

Take a blueprint item into your inventory and right-click the air. This starts
Axiom's Clone preview in its tenth hotbar slot and keeps you in creative mode.
Release the initial click before confirming.

- Scroll to move the preview.
- Middle-click to move it to the target.
- Ctrl+R rotates; Ctrl+F flips.
- Right-click confirms; left-click cancels.

These are Axiom's default controls. A confirmed placement leaves its repeat-copy
preview active. Cancel that preview before using builder undo. Existing builder
selections and editor placements must be finished or cancelled before starting
another blueprint. Switching tools cancels an imported preview without pasting.

## Library links

Items store a relative library path and a fallback name, not the build itself.
Editing the source changes future placements. Moving or deleting the file breaks
the link; the item remains in your inventory and reports the problem. Copy the
matching library files when moving a world to another computer.

Thumbnails are static, pixelated images up to 64 pixels across. Missing or invalid
images use a paper icon. Saved entities follow Axiom's **Copy Entities** setting
and are included in undo. Entity-only blueprints are currently unsupported.

Blueprint loading is limited to 128 MiB files and 4096 source chunks. Exceptionally
large or corrupt files are rejected. The integration targets Axiom 6.1.3 exactly.

## Building

With JDK 25 installed:

```sh
./gradlew clean build --warning-mode all
```

The jar is written to `build/libs/`. Run the isolated client regression test with
`./gradlew runClientGameTest --warning-mode all`, or launch the development client
with `./gradlew runClient`.

The build fetches Axiom for development; it does not bundle Axiom in the addon.
Kotlin and Java compiler warnings are treated as errors.
