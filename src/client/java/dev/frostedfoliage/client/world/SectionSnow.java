package dev.frostedfoliage.client.world;

import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.SnowMath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/** Main-thread capture; workers only read this immutable byte array. */
public final class SectionSnow {
    private final byte[] environment;
    private final boolean hasSnow;
    private SectionSnow(byte[] environment, boolean hasSnow) {
        this.environment = environment;
        this.hasSnow = hasSnow;
    }
    public boolean hasSnow() { return hasSnow; }
    public boolean sameEnvironment(SectionSnow other) {
        return other != null && java.util.Arrays.equals(environment, other.environment);
    }
    public int sample(BlockPos pos) { return environment[index(pos.getX(), pos.getY(), pos.getZ())] & 255; }
    private static int index(int x, int y, int z) { return (y & 15) << 8 | (z & 15) << 4 | x & 15; }

    public static SectionSnow capture(ClientLevel level, LevelChunk chunk, int sectionY, Foliage foliage, SnowConfig config) {
        byte[] data = new byte[4096];
        boolean any = false;
        LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX(), startZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int canopyTop = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            int solidTop = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = 0; y < 16; y++) {
                var state = section.getBlockState(x, y, z);
                if (!foliage.test(state) || (foliage.isPlant(state) && !state.getFluidState().isEmpty())) continue;
                pos.set(startX + x, SectionPos.sectionToBlockCoord(sectionY) + y, startZ + z);
                Biome biome = level.getBiome(pos).value();
                boolean snow = level.canHaveWeather() && biome.getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.SNOW;
                double exposure = SnowMath.exposure(snow, solidTop > pos.getY(), canopyTop - pos.getY(), config.skyExposureSensitivity());
                int amount = (int) Math.round(exposure * 127);
                if (amount > 0) {
                    data[index(x, y, z)] = (byte) (amount | (biome.getBaseTemperature() < 0 ? 128 : 0));
                    any = true;
                }
            }
        }
        return new SectionSnow(data, any);
    }
}
