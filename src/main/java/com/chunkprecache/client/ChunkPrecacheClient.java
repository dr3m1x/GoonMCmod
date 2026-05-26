package com.chunkprecache.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChunkPrecacheClient implements ClientModInitializer {
    
    private static KeyBinding openGuiKey;
    private static Config config;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("chunkprecache.json");
    
    private final Set<BlockPos> cachedBlocks = new HashSet<>();
    private int scanCooldown = 0;
    
    public static class Config {
        public boolean enableBlockPrecache = false;
        public boolean enableEntityPrecache = false;
        public int precacheRadius = 64;
        public int entityPrecacheRadius = 128;
        
        public float blockMarkerRed = 0.2f;
        public float blockMarkerGreen = 0.8f;
        public float blockMarkerBlue = 1.0f;
        public float blockMarkerAlpha = 0.25f;
        
        public float entityMarkerRed = 1.0f;
        public float entityMarkerGreen = 0.2f;
        public float entityMarkerBlue = 0.2f;
        public float entityMarkerAlpha = 0.3f;
        public boolean showEntitiesThroughWalls = true;
        
        public List<String> priorityBlocks = new ArrayList<>();
        public List<String> priorityEntities = new ArrayList<>();
    }
    
    private static void loadConfig() {
        try {
            if (CONFIG_PATH.toFile().exists()) {
                config = GSON.fromJson(new FileReader(CONFIG_PATH.toFile()), Config.class);
            } else {
                config = new Config();
                config.priorityBlocks.add("minecraft:diamond_ore");
                config.priorityBlocks.add("minecraft:deepslate_diamond_ore");
                config.priorityEntities.add("minecraft:player");
                config.priorityEntities.add("minecraft:enderman");
                saveConfig();
            }
        } catch (Exception e) {
            config = new Config();
        }
    }
    
    public static void saveConfig() {
        try {
            FileWriter writer = new FileWriter(CONFIG_PATH.toFile());
            GSON.toJson(config, writer);
            writer.close();
        } catch (Exception ignored) {}
    }
    
    @Override
    public void onInitializeClient() {
        loadConfig();
        
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chunkprecache.config",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_0,
            "category.chunkprecache"
        ));
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                client.setScreen(getConfigScreen(client.currentScreen));
            }
            
            if (!config.enableBlockPrecache || client.player == null || client.world == null) return;
            
            if (scanCooldown <= 0) {
                scanForBlocks(client);
                scanCooldown = 20;
            }
            scanCooldown--;
        });
        
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || context.world() == null) return;
            
            MatrixStack matrices = context.matrixStack();
            Camera camera = context.camera();
            Vec3d camPos = camera.getPos();
            
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            
            VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
            
            // Render blocks
            if (config.enableBlockPrecache && !cachedBlocks.isEmpty()) {
                RenderSystem.disableDepthTest();
                VertexConsumer lineBuffer = immediate.getBuffer(RenderLayer.getLines());
                VertexConsumer quadBuffer = immediate.getBuffer(RenderLayer.getDebugQuads());
                
                for (BlockPos pos : cachedBlocks) {
                    if (pos.isWithinDistance(client.player.getPos(), config.precacheRadius)) {
                        Box box = new Box(pos).offset(-camPos.x, -camPos.y, -camPos.z);
                        drawBoxOutline(matrices, lineBuffer, box, config.blockMarkerRed, config.blockMarkerGreen, config.blockMarkerBlue, 1.0f);
                        drawBoxFilled(matrices, quadBuffer, box, config.blockMarkerRed, config.blockMarkerGreen, config.blockMarkerBlue, config.blockMarkerAlpha);
                    }
                }
                RenderSystem.enableDepthTest();
            }
            
            // Render entities
            if (config.enableEntityPrecache) {
                if (config.showEntitiesThroughWalls) RenderSystem.disableDepthTest();
                VertexConsumer lineBuffer = immediate.getBuffer(RenderLayer.getLines());
                VertexConsumer quadBuffer = immediate.getBuffer(RenderLayer.getDebugQuads());
                
                for (Entity entity : context.world().getEntities()) {
                    if (entity == client.player || !entity.isAlive()) continue;
                    if (!entity.getPos().isInRange(client.player.getPos(), config.entityPrecacheRadius)) continue;
                    
                    Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
                    if (config.priorityEntities.contains(id.toString())) {
                        Box box = entity.getBoundingBox().offset(-camPos.x, -camPos.y, -camPos.z);
                        drawBoxOutline(matrices, lineBuffer, box, config.entityMarkerRed, config.entityMarkerGreen, config.entityMarkerBlue, 1.0f);
                        drawBoxFilled(matrices, quadBuffer, box, config.entityMarkerRed, config.entityMarkerGreen, config.entityMarkerBlue, config.entityMarkerAlpha);
                    }
                }
                if (config.showEntitiesThroughWalls) RenderSystem.enableDepthTest();
            }
            
            immediate.draw();
            RenderSystem.disableBlend();
        });
    }
    
    private void scanForBlocks(MinecraftClient client) {
        cachedBlocks.clear();
        if (client.player == null || client.world == null) return;
        
        BlockPos playerPos = client.player.getBlockPos();
        int radius = config.precacheRadius;
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    Block block = state.getBlock();
                    Identifier id = Registries.BLOCK.getId(block);
                    
                    if (config.priorityBlocks.contains(id.toString())) {
                        cachedBlocks.add(pos.toImmutable());
                    }
                }
            }
        }
    }
    
    private void drawBoxOutline(MatrixStack matrices, VertexConsumer vertexConsumer, Box box, float r, float g, float b, float a) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        
        vertexConsumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y1, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y1, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y1, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y1, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y1, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y1, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(0, 0, 0);
        
        vertexConsumer.vertex(matrix, x1, y2, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y2, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y2, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y2, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y2, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y2, z1).color(r, g, b, a).normal(0, 0, 0);
        
        vertexConsumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y2, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y1, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y2, z1).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y1, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y1, z2).color(r, g, b, a).normal(0, 0, 0);
        vertexConsumer.vertex(matrix, x1, y2, z2).color(r, g, b, a).normal(0, 0, 0);
    }
    
    private void drawBoxFilled(MatrixStack matrices, VertexConsumer buffer, Box box, float r, float g, float b, float a) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a);
        buffer.vertex(matrix, x1, y2, z1).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, z1).color(r, g, b, a);
        buffer.vertex(matrix, x2, y1, z1).color(r, g, b, a);
        
        buffer.vertex(matrix, x2, y1, z2).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a);
        buffer.vertex(matrix, x1, y2, z2).color(r, g, b, a);
        buffer.vertex(matrix, x1, y1, z2).color(r, g, b, a);
    }
    
    public static Screen getConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("ChunkPrecache Settings"))
            .setSavingRunnable(ChunkPrecacheClient::saveConfig);
        
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        
        ConfigCategory blocks = builder.getOrCreateCategory(Text.literal("Block Precaching"));
        blocks.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Block Precaching"), config.enableBlockPrecache)
            .setDefaultValue(false)
            .setSaveConsumer(newValue -> config.enableBlockPrecache = newValue)
            .setTooltip(Text.literal("Pre-renders important blocks to reduce chunk load stutter"))
            .build());
        
        blocks.addEntry(entryBuilder.startIntField(Text.literal("Block Precache Radius"), config.precacheRadius)
            .setDefaultValue(64)
            .setMin(16)
            .setMax(128)
            .setSaveConsumer(newValue -> config.precacheRadius = newValue)
            .setTooltip(Text.literal("Distance to scan for priority blocks"))
            .build());
        
        blocks.addEntry(entryBuilder.startFloatField(Text.literal("Block Marker Red"), config.blockMarkerRed)
            .setDefaultValue(0.2f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.blockMarkerRed = newValue)
            .build());
        
        blocks.addEntry(entryBuilder.startFloatField(Text.literal("Block Marker Green"), config.blockMarkerGreen)
            .setDefaultValue(0.8f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.blockMarkerGreen = newValue)
            .build());
        
        blocks.addEntry(entryBuilder.startFloatField(Text.literal("Block Marker Blue"), config.blockMarkerBlue)
            .setDefaultValue(1.0f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.blockMarkerBlue = newValue)
            .build());
        
        blocks.addEntry(entryBuilder.startFloatField(Text.literal("Block Marker Opacity"), config.blockMarkerAlpha)
            .setDefaultValue(0.25f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.blockMarkerAlpha = newValue)
            .setTooltip(Text.literal("Lower opacity reduces GPU overdraw"))
            .build());
        
        blocks.addEntry(entryBuilder.startStrList(Text.literal("Priority Blocks"), config.priorityBlocks)
            .setDefaultValue(new ArrayList<>())
            .setSaveConsumer(newValue -> config.priorityBlocks = newValue)
            .setTooltip(Text.literal("Format: minecraft:diamond_ore"))
            .build());
        
        ConfigCategory entities = builder.getOrCreateCategory(Text.literal("Entity Precaching"));
        entities.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Entity Precaching"), config.enableEntityPrecache)
            .setDefaultValue(false)
            .setSaveConsumer(newValue -> config.enableEntityPrecache = newValue)
            .setTooltip(Text.literal("Pre-renders entity bounding boxes to reduce culling calculations"))
            .build());
        
        entities.addEntry(entryBuilder.startIntField(Text.literal("Entity Precache Radius"), config.entityPrecacheRadius)
            .setDefaultValue(128)
            .setMin(16)
            .setMax(256)
            .setSaveConsumer(newValue -> config.entityPrecacheRadius = newValue)
            .setTooltip(Text.literal("Distance to pre-render entities"))
            .build());
        
        entities.addEntry(entryBuilder.startBooleanToggle(Text.literal("Render Through Walls"), config.showEntitiesThroughWalls)
            .setDefaultValue(true)
            .setSaveConsumer(newValue -> config.showEntitiesThroughWalls = newValue)
            .setTooltip(Text.literal("Disables depth testing to prevent z-fighting"))
            .build());
        
        entities.addEntry(entryBuilder.startFloatField(Text.literal("Entity Marker Red"), config.entityMarkerRed)
            .setDefaultValue(1.0f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.entityMarkerRed = newValue)
            .build());
        
        entities.addEntry(entryBuilder.startFloatField(Text.literal("Entity Marker Green"), config.entityMarkerGreen)
            .setDefaultValue(0.2f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.entityMarkerGreen = newValue)
            .build());
        
        entities.addEntry(entryBuilder.startFloatField(Text.literal("Entity Marker Blue"), config.entityMarkerBlue)
            .setDefaultValue(0.2f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.entityMarkerBlue = newValue)
            .build());
        
        entities.addEntry(entryBuilder.startFloatField(Text.literal("Entity Marker Opacity"), config.entityMarkerAlpha)
            .setDefaultValue(0.3f)
            .setMin(0.0f)
            .setMax(1.0f)
            .setSaveConsumer(newValue -> config.entityMarkerAlpha = newValue)
            .build());
        
        entities.addEntry(entryBuilder.startStrList(Text.literal("Priority Entities"), config.priorityEntities)
            .setDefaultValue(new ArrayList<>())
            .setSaveConsumer(newValue -> config.priorityEntities = newValue)
            .setTooltip(Text.literal("Format: minecraft:player, minecraft:enderman"))
            .build());
        
        return builder.build();
    }
                }
