# Vantage

A native Metal renderer, long-distance LOD terrain and a built-in shader look for Minecraft Java 26.3 on Apple
Silicon Macs. It's not a mod and needs no mod loader: it builds a drop-in replacement client jar that still joins vanilla
multiplayer servers.

- **Metal backend**: Minecraft's render API runs natively on Metal, with no OpenGL translation layer.
- **Farsight**: level-of-detail terrain far past the normal render distance (off by default: **Video Settings → Far
  Terrain**). In singleplayer it generates the far land from the world's seed with vanilla's own generator steps (real
  biomes, surface blocks, trees and structures), and real chunks replace it as you visit them. Far terrain casts shadows
  when the shaders are on.
- **Tessera**: a built-in shader look (off by default: **Video Settings → Shaders**). It has shadows, raymarched 3D
  clouds, god rays, water reflections, bloom, ambient occlusion, TAA and a filmic tonemap.
- **Render Scale**: draws the world at a share of the window's resolution and scales it up, while the GUI stays sharp
  (85 % by default).
- **Zoom**: hold C to zoom in (rebind it under **Controls → Miscellaneous**).

On an M5 MacBook at 3024 × 1964 with shaders and far terrain on, flying over new land runs at about 200 FPS on average
(1 % lows around 165).

## Requirements

- An Apple Silicon Mac on **macOS 26 or newer**
- The Xcode command line tools (`xcode-select --install`; they include git, Python 3 and clang)
- A Minecraft Java Edition account, and a launcher that can replace the game jar (for example
  [Prism Launcher](https://prismlauncher.org))

You don't need to install Java: the setup downloads Mojang's own Java runtime along with the game.

## Quick start

There is no prebuilt download: the finished jar contains Minecraft's code, so you build it on your own Mac. The build
downloads vanilla 26.3 from Mojang, decompiles it, applies `patches/` and compiles the result (a few minutes).

```sh
git clone https://github.com/imjameslin/vantage.git
cd vantage
zsh setup.sh                 # downloads vanilla 26.3 from Mojang, decompiles it, applies patches/
zsh tools/build-client.sh    # -> build/client.jar
```

Then play it in Prism Launcher:

1. **Add Instance** → Minecraft **26.3** (no mod loader).
2. **Edit** → **Version** → **Replace Minecraft.jar** → choose `build/client.jar`.
3. Launch and log in as usual.
4. Turn on **Video Settings → Shaders** and **Video Settings → Far Terrain**, then restart the game.

To update: `git pull`, then `zsh setup.sh` (it reuses the download and decompile and only re-applies `patches/`) and
`zsh tools/build-client.sh`, and replace the jar in the instance the same way.

## Settings

Two settings in **Video Settings** take effect the next time the game starts:

- **Shaders**: off by default.
- **Far Terrain** (Farsight): off by default.

Launch flags override them: `-Dmc.shaders=true|false` and `-Dmc.lod=true|false`. **Render Scale** and **Far Terrain
Quality** apply right away.

### Far terrain on servers

Farsight never requests anything from a server. On a multiplayer server it shows the chunks you have visited, plus
generated land when you give it the server's seed: `-Dmc.lod.seed=<seed>`, or a line `<server address>=<seed>` in
`<game dir>/lod/seeds.properties`.

## Known limitations

- Apple Silicon Macs and Minecraft Java 26.3 only. It has been tested on one machine (M5) so far.
- Right after joining a world, distant terrain takes some time (often 30-60 s) to fill in to the horizon.
- While far terrain is still loading, the game can stutter briefly when Farsight takes another block of GPU memory.
- Shader packs and mods aren't supported. Tessera is built in.

## Repository layout

| Path | What it holds |
|---|---|
| `native/` | The Metal backend in Objective-C (`libmcmetal.dylib`) |
| `client/src/` | New Java classes: the Metal device, Farsight and Tessera |
| `client/resources/` | New shaders and assets |
| `patches/` | Changes to vanilla classes and shaders, as diffs that `setup.sh` applies |
| `bench/` | The benchmark and screenshot agent used to measure every change |
| `tools/` | Build, benchmark and test scripts |
| `docs/` | Design notes |

## Legal

Vantage's own code is MIT licensed (see `LICENSE`). This repository doesn't contain any Minecraft code or assets.
`patches/` holds only changes, which apply to a copy of Minecraft you download yourself. This is not an official
Minecraft product and is not approved by or associated with Mojang or Microsoft.
