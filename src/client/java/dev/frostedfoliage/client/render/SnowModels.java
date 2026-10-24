package dev.frostedfoliage.client.render;

import dev.frostedfoliage.client.world.SnowSystem;
import dev.frostedfoliage.client.world.PlantTypes;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.HeightClipper;
import dev.frostedfoliage.core.SnowMath;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import static dev.frostedfoliage.client.render.SnowSpriteSource.LEAF_STRIDE;
import static dev.frostedfoliage.client.render.SnowSpriteSource.PLANT_STRIDE;
import static dev.frostedfoliage.client.render.SnowSpriteSource.STAGES;

public final class SnowModels {
    private SnowModels() { }
    public static void register(SnowSystem system) {
        ModelLoadingPlugin.register(context -> {
            // One atlas bank per resource reload, shared by every model and safely published.
            Bank[] bank = new Bank[1];
            context.modifyBlockModelAfterBake().register(ModelModifier.WRAP_LAST_PHASE, (model, bake) -> {
                synchronized (bank) { if (bank[0] == null) bank[0] = new Bank(bake.baker().materials()); }
                return new SnowModel(model, system, bank[0]);
            });
        });
    }

    private static final class Bank {
        final SpriteFinder finder;
        final Material.Baked groundSnow;
        final Map<Identifier, TextureAtlasSprite[]> sprites;
        final Map<Identifier, TextureAtlasSprite[]> plantSprites;
        final double depth;
        final java.util.Set<Identifier> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final ThreadLocal<Worker> workers = ThreadLocal.withInitial(Worker::new);
        Bank(MaterialBaker baker) {
            finder = baker.spriteFinder(QuadAtlas.BLOCK);
            groundSnow = baker.get(new Material(Identifier.withDefaultNamespace("block/snow")), () -> "ground snow");
            var catalog = SnowSpriteSource.catalog();
            depth = catalog.depth();
            Map<Identifier, TextureAtlasSprite[]> map = new HashMap<>();
            for (Identifier original : catalog.sources()) {
                TextureAtlasSprite[] variants = new TextureAtlasSprite[LEAF_STRIDE * catalog.edges()];
                boolean valid = true;
                for (int edge = 0; edge < catalog.edges(); edge++) for (int v = 0; v < SnowSpriteSource.VARIANTS; v++) for (int s = 1; s <= STAGES; s++) {
                    int stage = catalog.profile().stage(s);
                    if (stage == 0) continue;
                    var material = baker.get(new Material(SnowSpriteSource.snowId(original, stage, v, edge)), () -> "frostedfoliage masks");
                    variants[edge * LEAF_STRIDE + v * STAGES + s - 1] = material.sprite();
                    valid &= !material.sprite().contents().name().equals(MissingTextureAtlasSprite.getLocation());
                }
                if (valid) map.put(original, variants);
            }
            sprites = Map.copyOf(map);
            var plantMap = new HashMap<Identifier, TextureAtlasSprite[]>();
            catalog.plantSources().forEach((original, parts) -> {
                TextureAtlasSprite[] variants = new TextureAtlasSprite[4 * PLANT_STRIDE];
                boolean valid = true;
                for (int shape = 0; shape < 4; shape++) {
                    if (shape > 0 && !parts.contains(shape - 1)) continue;
                    for (int v = 0; v < SnowSpriteSource.PLANT_VARIANTS; v++) for (int s = 1; s <= STAGES; s++) {
                        int stage = catalog.profile().stage(s);
                        if (stage == 0) continue;
                        var material = baker.get(new Material(SnowSpriteSource.plantSnowId(original, stage, v, shape)), () -> "frostedfoliage plant masks");
                        variants[shape * PLANT_STRIDE + v * STAGES + s - 1] = visible(material.sprite()) ? material.sprite() : null;
                        valid &= !material.sprite().contents().name().equals(MissingTextureAtlasSprite.getLocation());
                    }
                }
                if (valid) plantMap.put(original, variants);
            });
            plantSprites = Map.copyOf(plantMap);
            dev.frostedfoliage.client.FrostedFoliageClient.LOGGER.info("Snow atlas bank ready: {} leaf textures, {} plant textures", sprites.size(), plantSprites.size());
        }

        private static boolean visible(TextureAtlasSprite sprite) {
            var contents = sprite.contents();
            for (int frame : contents.getUniqueFrames()) for (int y = 0; y < contents.height(); y++) for (int x = 0; x < contents.width(); x++) {
                if (!contents.isTransparent(frame, x, y)) return true;
            }
            return false;
        }
    }

    private static final class SnowModel extends WrapperBlockStateModel {
        private final SnowSystem system;
        private final Bank bank;
        SnowModel(BlockStateModel wrapped, SnowSystem system, Bank bank) { super(wrapped); this.system = system; this.bank = bank; }

        @Override public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
            return (system.active() && system.foliage().test(state)) || system.ground().layers(pos) > 0
                    ? null : wrapped.createGeometryKey(level, pos, state, random);
        }

        @Override public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                                        RandomSource random, Predicate<Direction> cullTest) {
            if (level != BlockAndTintGetter.EMPTY) emitGround(emitter, system.ground().layers(pos));
            boolean plant = system.foliage().isPlant(state);
            double amount = system.foliage().test(state) && level != BlockAndTintGetter.EMPTY ? system.coverage(pos, plant) : 0;
            if (amount <= 0) { wrapped.emitQuads(emitter, level, pos, state, random, cullTest); return; }
            Worker worker = bank.workers.get();
            // Nested custom models must not overwrite an outer invocation's context.
            if (worker.output != null) worker = new Worker();
            worker.output = emitter; worker.bank = bank; worker.amount = amount; worker.config = system.config();
            worker.plant = plant; worker.plantPart = PlantTypes.part(state);
            worker.plantVariant = SnowMath.noise(pos.getX(), pos.getY(), pos.getZ()) < 0.5 ? 0 : 1;
            worker.blockX = pos.getX(); worker.blockY = pos.getY(); worker.blockZ = pos.getZ();
            int open = 0;
            if (!plant) for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState neighbor = level.getBlockState(pos.relative(d));
                if (!system.foliage().test(neighbor) || system.foliage().isPlant(neighbor)) open++;
            }
            worker.exterior = 0.7 + open * 0.075;
            try { wrapped.emitQuads(worker.input, level, pos, state, random, cullTest); }
            finally { worker.output = null; worker.bank = null; worker.config = null; }
        }

        private void emitGround(QuadEmitter emitter, int layers) {
            if (layers == 0) return;
            float height = layers / 8f;
            for (Direction face : Direction.values()) {
                if (face == Direction.DOWN) continue;
                if (face == Direction.UP) emitter.square(face, 0, 0, 1, 1, 1 - height);
                else emitter.square(face, 0, 0, 1, height, 0);
                emitter.cullFace(null).tintIndex(-1).color(-1, -1, -1, -1).chunkLayer(ChunkSectionLayer.SOLID);
                emitter.materialBake(bank.groundSnow, MutableQuadView.BAKE_LOCK_UV).translate(0, 1, 0).emit();
            }
        }
    }

    /** Reused per worker thread, with no retained world/position/model references between calls. */
    private static final class Worker {
        final QuadEmitter input = Renderer.get().quadEmitter(this::accept);
        final HeightClipper clipper = new HeightClipper();
        QuadEmitter output;
        Bank bank;
        SnowConfig config;
        double amount, exterior;
        int plantVariant, plantPart, blockX, blockY, blockZ;
        boolean plant;
        void accept(MutableQuadView quad) {
            output.copyFrom(quad);
            // Sodium 0.9.2 copyFrom copies the valid-geometry flag but omits its cached normal facing.
            // Re-setting one unchanged position refreshes that derived state through the public API.
            output.pos(0, quad.x(0), quad.y(0), quad.z(0));
            output.emit();
            if (plant) { emitPlant(quad); return; }
            Direction face = quad.lightFace();
            double depth = config.snowDepth();
            if (depth < 1 && (face == Direction.DOWN || (depth == 0 && face != Direction.UP))) return;
            boolean clipped = depth < 1 && face != Direction.UP;
            int vertices = clipped ? clipper.clip(quad.y(0), quad.y(1), quad.y(2), quad.y(3), (float) (1 - depth)) : 4;
            if (vertices == 0) return;
            double faceWeight = config.preferTopFaces() ? (face == Direction.UP ? 1 : face == Direction.DOWN ? 0.15 : 0.8) : 1;
            double coverage = SnowMath.coverage(amount, faceWeight * (face == Direction.UP ? 1 : exterior));
            int stage = Math.min(16, (int) Math.floor(coverage * 16 + 0.5));
            if (stage == 0) return;
            TextureAtlasSprite original = bank.finder.find(quad);
            TextureAtlasSprite[] masks = bank.sprites.get(original.contents().name());
            if (masks == null) {
                if (bank.missing.size() < 32 && bank.missing.add(original.contents().name())) {
                    dev.frostedfoliage.client.FrostedFoliageClient.LOGGER.warn("No snow mask for foliage sprite {} (UV {} {})", original.contents().name(), quad.u(0), quad.v(0));
                }
                return;
            }
            int edge = 0;
            if (clipped && masks.length > LEAF_STRIDE && Math.abs(bank.depth - depth) < 1e-6) {
                float largestDy = 0;
                float smallestUv = Float.POSITIVE_INFINITY;
                for (int i = 1; i < 4; i++) {
                    float dy = quad.y(i) - quad.y(0);
                    float du = (quad.u(i) - quad.u(0)) / (original.getU1() - original.getU0());
                    float dv = (quad.v(i) - quad.v(0)) / (original.getV1() - original.getV0());
                    float uvDistance = du * du + dv * dv;
                    if (Math.abs(dy) > largestDy || (Math.abs(dy) == largestDy && uvDistance < smallestUv)) {
                        largestDy = Math.abs(dy);
                        smallestUv = uvDistance;
                        edge = SnowMath.depthEdge(dy, du, dv);
                    }
                }
                // Unusual shifted/cropped UVs retain geometrically clipped snow instead of a misplaced fade.
                for (int i = 0; i < 4 && edge != 0; i++) {
                    if (!SnowMath.depthMappingMatches(edge, quad.y(i),
                            (quad.u(i) - original.getU0()) / (original.getU1() - original.getU0()),
                            (quad.v(i) - original.getV0()) / (original.getV1() - original.getV0()))) edge = 0;
                }
            }
            int variant = SnowMath.leafVariant(blockX, blockY, blockZ, face.ordinal());
            TextureAtlasSprite mask = masks[edge * LEAF_STRIDE + variant * STAGES + stage - 1];
            if (mask == null) return;
            for (int i = 0; i < 4; i++) {
                float u = (quad.u(i) - original.getU0()) / (original.getU1() - original.getU0());
                float v = (quad.v(i) - original.getV0()) / (original.getV1() - original.getV0());
                quad.uv(i, mask.getU(u), mask.getV(v));
            }
            int shade = (int) (180 + 75 * config.whiteness());
            int color = 0xff000000 | shade << 16 | shade << 8 | shade;
            quad.color(color, color, color, color);
            quad.tintIndex(-1).chunkLayer(ChunkSectionLayer.CUTOUT).animated(mask.isAnimated()).emissive(false);
            if (!clipped) {
                output.copyFrom(quad);
                var normal = quad.faceNormal();
                output.translate(normal.x() * 0.002f, normal.y() * 0.002f, normal.z() * 0.002f).emit();
            } else if (vertices <= 4) {
                emitClipped(quad, 0, 1, 2, vertices == 4 ? 3 : 2);
            } else {
                // A sloped custom quad can clip to a pentagon; preserve it as a triangle fan.
                for (int i = 1; i < vertices - 1; i++) emitClipped(quad, 0, i, i + 1, i + 1);
            }
        }

        private void emitPlant(MutableQuadView quad) {
            if (!config.plantsEnabled() || config.plantSnowDepth() <= 0 || quad.emissive()) return;
            var normal = quad.faceNormal();
            if (normal.y() < -0.5f && config.plantSnowDepth() < 1) return;
            int stage = Math.min(16, (int) Math.floor(amount * 16 + 0.5));
            if (stage == 0) return;
            TextureAtlasSprite original = bank.finder.find(quad);
            TextureAtlasSprite[] masks = bank.plantSprites.get(original.contents().name());
            if (masks == null) return; // Excludes stems and emissive overlays without replacing their base model.
            // Crossed plant quads may use UP for lighting despite having vertical geometry.
            int shape = normal.y() > 0.9f ? 0 : plantPart + 1;
            TextureAtlasSprite mask = masks[shape * PLANT_STRIDE + plantVariant * STAGES + stage - 1];
            if (mask == null) return;
            for (int i = 0; i < 4; i++) {
                float u = (quad.u(i) - original.getU0()) / (original.getU1() - original.getU0());
                float v = (quad.v(i) - original.getV0()) / (original.getV1() - original.getV0());
                quad.uv(i, mask.getU(u), mask.getV(v));
            }
            int shade = (int) (180 + 75 * config.whiteness());
            int color = 0xff000000 | shade << 16 | shade << 8 | shade;
            quad.color(color, color, color, color);
            quad.tintIndex(-1).chunkLayer(ChunkSectionLayer.CUTOUT).animated(mask.isAnimated()).emissive(false);
            output.copyFrom(quad);
            output.translate(normal.x() * 0.002f, normal.y() * 0.002f, normal.z() * 0.002f).emit();
        }

        private void emitClipped(MutableQuadView quad, int a, int b, int c, int d) {
            output.copyFrom(quad);
            clippedVertex(quad, 0, a); clippedVertex(quad, 1, b);
            clippedVertex(quad, 2, c); clippedVertex(quad, 3, d);
            output.emit();
        }

        private void clippedVertex(MutableQuadView quad, int target, int vertex) {
            int a = clipper.from(vertex), b = clipper.to(vertex);
            var normal = quad.faceNormal();
            // Interpolate the original UVs with the geometry, rather than stretching the snow texture.
            output.pos(target, clipper.interpolate(vertex, quad.x(a), quad.x(b)) + normal.x() * 0.002f,
                    clipper.interpolate(vertex, quad.y(a), quad.y(b)) + normal.y() * 0.002f,
                    clipper.interpolate(vertex, quad.z(a), quad.z(b)) + normal.z() * 0.002f);
            output.uv(target, clipper.interpolate(vertex, quad.u(a), quad.u(b)), clipper.interpolate(vertex, quad.v(a), quad.v(b)));
            int la = quad.lightmap(a), lb = quad.lightmap(b);
            int block = Math.round(clipper.interpolate(vertex, la & 0xffff, lb & 0xffff));
            int sky = Math.round(clipper.interpolate(vertex, la >>> 16, lb >>> 16));
            output.lightmap(target, block | sky << 16);
            float nx = clipper.interpolate(vertex, quad.hasNormal(a) ? quad.normalX(a) : normal.x(), quad.hasNormal(b) ? quad.normalX(b) : normal.x());
            float ny = clipper.interpolate(vertex, quad.hasNormal(a) ? quad.normalY(a) : normal.y(), quad.hasNormal(b) ? quad.normalY(b) : normal.y());
            float nz = clipper.interpolate(vertex, quad.hasNormal(a) ? quad.normalZ(a) : normal.z(), quad.hasNormal(b) ? quad.normalZ(b) : normal.z());
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1e-6f) output.normal(target, nx / length, ny / length, nz / length);
            else output.normal(target, normal.x(), normal.y(), normal.z());
        }
    }
}
