# Frosted Foliage

Snow that settles on your trees, grass, and flowers.

Frosted Foliage is a client-side Fabric mod for **Minecraft Java 26.3**. Leaves and plants gradually gather snow during snowfall, then thaw when the weather clears. The coating follows your resource pack's foliage textures, keeping their shapes and detail visible.

![Snow-covered spruce trees with green lower branches in warm evening light](https://github.com/user-attachments/assets/39e889cf-5bd3-42f2-9a5d-65db9d9ad085)

*Gameplay screenshots use Complementary Reimagined shaders.*

https://github.com/user-attachments/assets/e2ab33a6-5dec-4d90-b7f9-0bff27126a68

*Snow building up during snowfall, shown at 12× speed.*

## Features

- **Gradual snowfall and melting.** Exposed foliage gathers snow first, with slower buildup beneath tree canopies and slower melting in snowy biomes such as snowy plains and snowy taiga.
- **Separate controls for trees and plants.** Choose how far snow reaches down the foliage and how much of its texture turns white.
- **Optional year-round frost.** Keep foliage in snowy biomes frosted even between snowfalls.
- **Snow beneath the canopy.** Fill bare patches under trees and plants when nearby ground already has snow.
- **Resource pack support.** Snow follows the active foliage textures, including transparent gaps. No extra texture pack is needed.
- **Entirely visual.** Use it in singleplayer or on servers without installing anything on the server. Blocks, collision, and world saves stay unchanged.

![Frosted fern tips and green lower fronds beneath snowy spruce trees](https://github.com/user-attachments/assets/e26c685a-c489-4a47-bc3e-ba484ef28cca)

*Snow settles on tall ferns beneath the canopy.*

## Installation

You will need:

| Requirement | Version |
| --- | --- |
| Minecraft Java Edition | 26.3 |
| Java | 25 or newer |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.164.0+26.3 or a compatible newer version for Minecraft 26.3 |

1. Set up a Fabric instance for Minecraft 26.3 in your launcher.
2. Add Fabric API and the Frosted Foliage mod JAR to that instance's `mods` folder.
3. Launch the game and visit a snowy area during snowfall. With the default settings, buildup takes about three minutes of steady snow.

For an in-game settings screen, also install **Mod Menu** and **Cloth Config** for Minecraft 26.3. Both are optional.

## Settings

Open **Mods → Frosted Foliage** and select its configuration button.

![Frosted Foliage Plants settings with snow enabled, 35% depth, 60% coverage, and ground snow enabled](https://github.com/user-attachments/assets/9fe78dba-baa4-4f97-ae23-7780fc8a2b5f)

Settings are grouped into five tabs:

| Tab | Controls |
| --- | --- |
| Appearance | Enable the effect, adjust snow whiteness, and vary buildup between blocks. |
| Trees | Set leaf snow depth and coverage, coat tops first, and toggle ground snow beneath trees. |
| Plants | Enable plant frost, set its depth and coverage, and toggle ground snow beneath plants. |
| Weather | Adjust buildup time, melting, and the delay beneath canopies, or keep snowy biomes frosted. |
| Advanced | Adjust quality and performance, enable modded foliage, or customize which blocks receive snow. |

**Depth** controls how far snow reaches down a leaf block or plant. For leaves, 0% coats only the tops and 100% coats the whole block, including undersides. For plants, 0% turns the coating off and 100% reaches the base.

**Coverage** controls how much of the texture turns snowy. Lower values leave more green leaves and flower colors showing. Trees default to 50% depth and 100% coverage. Plants default to 35% depth and 60% coverage.

![Red poppies with white frost on the upper petals](https://github.com/user-attachments/assets/61562ba2-1467-48a2-acde-9de69fb637cf)

*Plant frost at the default 35% depth and 60% coverage.*

Changes apply while playing. Some changes, including snow depth, trigger a brief resource reload; their tooltips say so.

To see your settings without waiting for snowfall, stand in a snowy area and run `/frostedfoliage preview`. Foliage shows full snow until you run the command again or leave the world.

Without the settings screen, edit `config/frostedfoliage.json` in your instance folder and run `/frostedfoliage reload` in chat.

## Foliage and compatibility

Frosted Foliage supports vanilla leaves, grass, ferns, flowers, bushes, azaleas, and saplings. Many modded leaves and plants are recognized automatically; custom foliage may need the options in **Advanced**. Crops, aquatic plants, fungi, and potted plants are excluded. Waterlogged plants stay clear.

The mod works with Minecraft's standard renderer, Sodium, and Iris. Resource packs and shaders can change the final appearance; packs with custom foliage models or rendering may need additional compatibility work.

## Common questions

### Why isn't snow appearing?

Foliage needs active snowfall in a cold enough location. Rain in warm biomes does not count, solid roofs provide shelter, and leaves overhead slow buildup. Allow a few minutes for snow to collect, and check that the effect is enabled in settings.

### Does it work in an already snowy world?

Ground snow can appear beneath trees and plants as soon as nearby real snow is present. Frost on the foliage itself builds during snowfall; it starts fresh when you join a world or change dimensions. To keep snowy biomes frosted all the time, turn on **Weather → Snowy biomes stay frosted**.

### Why are there still bare patches beneath some trees?

Ground snow follows nearby real snow layers. Very wide canopies or uneven terrain can leave gaps. You can toggle ground snow separately for trees and plants.

### Where is the settings button?

Install both Mod Menu and Cloth Config for your Minecraft version, then restart the game.

## License

Frosted Foliage is licensed under the **GNU Lesser General Public License v3.0 or later** (`LGPL-3.0-or-later`). See [LICENSE](LICENSE), [COPYING.LESSER](COPYING.LESSER), and [COPYING](COPYING) for the full terms.

## Development

Install JDK 25 and use the included Gradle wrapper:

```sh
./gradlew build
```

The mod JAR is written to `build/libs/frostedfoliage-0.1.0.jar`. To launch a development client:

```sh
./gradlew runClient
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

### Testing in a launcher

`./gradlew clientTestJar` builds a separate test mod in `build/testmods/`. To run the client game tests against the release jar, use a disposable launcher instance. The tests create worlds and change video settings.

1. Put the mod jar, the test mod, and Fabric's `fabric-client-gametest-api-v1` jar in the instance's `mods` folder. The Fabric API download does not include that module.
2. Add `-Dfabric.client.gametest` to the instance's Java arguments.
3. Choose scenarios in `config/frostedfoliage-test.properties`, for example `ground=true`. The names match the `frostedfoliage.test.*` flags set in `build.gradle`. This file is easier to change than Java arguments, which some launchers cache.

Never give the test mod to players.
