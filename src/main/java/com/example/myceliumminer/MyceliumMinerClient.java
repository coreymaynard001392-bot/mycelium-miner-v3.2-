package com.example.myceliumminer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

public class MyceliumMinerClient implements ClientModInitializer {
    public enum Mode { OFF, MYCELIUM, STONE }
    public static final List<Vec3> ROUTE = new ArrayList<>();
    public static final List<BlockPos> JUMP_POINTS = new ArrayList<>();
    public static final List<BlockPos> MINE_POINTS = new ArrayList<>();
    // Add only the blocks you want mined. An arbitrary concave outline is supported.
    public static final List<BlockPos> STONE_BLOCKS = new ArrayList<>();
    public static Mode mode = Mode.OFF;
    public static boolean recording = false;
    public static boolean recordingStone = false;
    private static boolean previousStoneClick = false;
    public static float turnSpeed = 5.0f;
    public static float turnAcceleration = 0.65f;
    private static float yawVelocity, pitchVelocity;
    private static boolean previousRecordingJump;
    private static int routeIndex, mineIndex, stoneIndex, recordCooldown, jumpCooldown;
    private static Vec3 lastRecorded;
    private static BlockPos lastJumpPoint;
    private static KeyMapping openKey, toggleKey, recordKey, stoneRecordKey;
    private static boolean ownedForward, ownedJump, ownedAttack, ownedSprint;

    @Override public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("myceliumminer", "controls"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.myceliumminer.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.myceliumminer.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.myceliumminer.record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, category));
        stoneRecordKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.myceliumminer.stonerecord", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        ClientTickEvents.END_CLIENT_TICK.register(MyceliumMinerClient::tick);
    }

    public static void stop(Minecraft client) { mode = Mode.OFF; release(client); }
    public static void start(Mode next, Minecraft client) {
        release(client);
        mode = next;
        routeIndex = mineIndex = stoneIndex = 0;
        jumpCooldown = 0;
        yawVelocity = pitchVelocity = 0;
        lastJumpPoint = null;
    }
    public static void resetRouteProgress() { routeIndex = mineIndex = 0; jumpCooldown = 0; lastJumpPoint = null; }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { mode = Mode.OFF; release(mc); return; }
        while (openKey.consumeClick()) { stop(mc); mc.setScreen(new MinerScreen()); }
        while (toggleKey.consumeClick()) {
            if (mode == Mode.OFF) start(Mode.MYCELIUM, mc); else stop(mc);
            mc.player.displayClientMessage(Component.literal("Macro: " + mode), true);
        }
        while (stoneRecordKey.consumeClick()) {
            recordingStone = !recordingStone;
            previousStoneClick = false;
            stop(mc);
            mc.player.displayClientMessage(Component.literal(recordingStone ? "Stone recording: left-click target blocks (F8 to stop)" : "Stone recording stopped"), true);
        }
        if (recordingStone && mc.screen == null) {
            boolean clicked = mc.options.keyAttack.isDown();
            if (clicked && !previousStoneClick && mc.hitResult instanceof BlockHitResult hit) {
                BlockPos pos = hit.getBlockPos();
                if (!STONE_BLOCKS.contains(pos)) {
                    STONE_BLOCKS.add(pos);
                    mc.player.displayClientMessage(Component.literal("Stone target added: " + pos.toShortString()), true);
                }
            }
            previousStoneClick = clicked;
        } else if (!recordingStone) previousStoneClick = false;
        while (recordKey.consumeClick()) {
            recording = !recording;
            lastRecorded = null;
            previousRecordingJump = false;
            mc.player.displayClientMessage(Component.literal(recording ? "Recording route" : "Route recording stopped"), true);
        }
        if (recording && mc.screen == null) {
            // Record jump activation at the location where Space was pressed.
            boolean jumping = mc.options.keyJump.isDown();
            if (jumping && !previousRecordingJump) {
                BlockPos jump = mc.player.blockPosition();
                if (!JUMP_POINTS.contains(jump)) JUMP_POINTS.add(jump);
                Vec3 point = mc.player.position();
                if (lastRecorded == null || point.distanceTo(lastRecorded) > 0.15) {
                    ROUTE.add(point);
                    lastRecorded = point;
                }
            }
            previousRecordingJump = jumping;
            if (recordCooldown-- <= 0) {
                recordCooldown = 5;
                Vec3 here = mc.player.position();
                if (lastRecorded == null || here.distanceTo(lastRecorded) > 0.65) {
                    ROUTE.add(here);
                    lastRecorded = here;
                }
            }
        } else if (!recording) {
            previousRecordingJump = false;
        }
        if (mc.screen != null || mode == Mode.OFF || recordingStone) { release(mc); return; }
        if (mode == Mode.STONE) {
            releaseForwardAndJump(mc);
            mineList(mc, STONE_BLOCKS, true);
            return;
        }
        if (ROUTE.isEmpty() && MINE_POINTS.isEmpty()) {
            stop(mc);
            mc.player.displayClientMessage(Component.literal("Add route or mining points first"), true);
            return;
        }
        // Mining takes priority only when a target is in reach.
        if (mineList(mc, MINE_POINTS, false)) { releaseForwardAndJump(mc); return; }
        releaseAttack(mc);
        Vec3 here = mc.player.position();
        // Without a separate route, mining coordinates are walking destinations.
        Vec3 dest = ROUTE.isEmpty()
                ? Vec3.atCenterOf(MINE_POINTS.get(mineIndex % MINE_POINTS.size()))
                : ROUTE.get(routeIndex % ROUTE.size());
        double dx = dest.x - here.x, dz = dest.z - here.z;
        double distance = Math.hypot(dx, dz);
        if (distance < 0.65) {
            if (!ROUTE.isEmpty()) routeIndex = (routeIndex + 1) % ROUTE.size();
            setForward(mc, false);
            setSprint(mc, false);
        }
        else {
            float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90);
            turn(mc, yaw, mc.player.getXRot());
            // Keep walking/sprinting while steering; a narrow yaw gate made corners slow.
            setForward(mc, true);
            setSprint(mc, true);
        }
        if (jumpCooldown > 0) jumpCooldown--;
        setJump(mc, false);
        if (lastJumpPoint != null && here.distanceTo(Vec3.atCenterOf(lastJumpPoint)) > 1.5) lastJumpPoint = null;
        if (jumpCooldown == 0 && lastJumpPoint == null) {
            for (BlockPos p : JUMP_POINTS) {
                if (Math.hypot(here.x - (p.getX() + 0.5), here.z - (p.getZ() + 0.5)) < 0.42
                        && Math.abs(here.y - p.getY()) < 1.6) {
                    setJump(mc, true); jumpCooldown = 8; lastJumpPoint = p; break;
                }
            }
        }
    }

    // Returns true when the route macro should stop walking to mine a nearby target.
    private static boolean mineList(Minecraft mc, List<BlockPos> blocks, boolean generator) {
        if (blocks.isEmpty()) { releaseAttack(mc); return false; }
        int index = generator ? stoneIndex : mineIndex;
        if (index >= blocks.size()) index = 0;
        // In generator mode, continue cycling, including positions that regenerate.
        // In route mode, skip air targets and continue walking.
        for (int tries = 0; tries < blocks.size(); tries++) {
            BlockPos p = blocks.get(index);
            if (!mc.level.getBlockState(p).isAir()) break;
            index = (index + 1) % blocks.size();
        }
        if (generator) stoneIndex = index; else mineIndex = index;
        BlockPos target = blocks.get(index);
        if (mc.level.getBlockState(target).isAir()) { releaseAttack(mc); return generator; }
        Vec3 aim = Vec3.atCenterOf(target).subtract(mc.player.getEyePosition());
        if (aim.length() > 4.4) { releaseAttack(mc); return generator; }
        float yaw = (float)(Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90);
        float pitch = (float)-Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z)));
        turn(mc, yaw, pitch);
        boolean aligned = Math.abs(Mth.wrapDegrees(yaw - mc.player.getYRot())) < 3.0
                && Math.abs(pitch - mc.player.getXRot()) < 3.0;
        boolean onBlock = mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(target);
        setAttack(mc, aligned && onBlock);
        // After the target is mined, the next tick skips air blocks.
        return true;
    }

    private static void turn(Minecraft mc, float yaw, float pitch) {
        // Velocity-limited, eased steering avoids repeated constant-size micro-snaps.
        // Minecraft still applies this on client ticks; full per-frame rendering
        // interpolation would require a separate render integration.
        float maxSpeed = Mth.clamp(turnSpeed, 1f, 12f);
        float accel = Mth.clamp(turnAcceleration, 0.2f, 2.0f);
        float yawError = Mth.wrapDegrees(yaw - mc.player.getYRot());
        float pitchError = Mth.clamp(pitch - mc.player.getXRot(), -180f, 180f);
        float desiredYaw = Mth.clamp(yawError * 0.18f, -maxSpeed, maxSpeed);
        float desiredPitch = Mth.clamp(pitchError * 0.18f, -maxSpeed, maxSpeed);
        yawVelocity += Mth.clamp(desiredYaw - yawVelocity, -accel, accel);
        pitchVelocity += Mth.clamp(desiredPitch - pitchVelocity, -accel, accel);
        if (Math.abs(yawError) < 0.15f) yawVelocity = yawError;
        if (Math.abs(pitchError) < 0.15f) pitchVelocity = pitchError;
        float yawStep = Math.abs(yawVelocity) > Math.abs(yawError) ? yawError : yawVelocity;
        float pitchStep = Math.abs(pitchVelocity) > Math.abs(pitchError) ? pitchError : pitchVelocity;
        mc.player.setYRot(mc.player.getYRot() + yawStep);
        mc.player.setYHeadRot(mc.player.getYRot());
        mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitchStep, -90, 90));
    }

    // Only override keys while the macro runs. When stopping, restore current physical input.
    private static void setForward(Minecraft mc, boolean value) { mc.options.keyUp.setDown(value); ownedForward = true; }
    private static void setSprint(Minecraft mc, boolean value) { mc.options.keySprint.setDown(value); ownedSprint = true; }
    private static void setJump(Minecraft mc, boolean value) { mc.options.keyJump.setDown(value); ownedJump = true; }
    private static void setAttack(Minecraft mc, boolean value) { mc.options.keyAttack.setDown(value); ownedAttack = true; }
    private static void releaseForwardAndJump(Minecraft mc) {
        if (mc.options == null) return;
        if (ownedForward) { restore(mc, mc.options.keyUp); ownedForward = false; }
        if (ownedJump) { restore(mc, mc.options.keyJump); ownedJump = false; }
        if (ownedSprint) { restore(mc, mc.options.keySprint); ownedSprint = false; }
    }
    private static void releaseAttack(Minecraft mc) {
        if (mc.options != null && ownedAttack) { restore(mc, mc.options.keyAttack); ownedAttack = false; }
    }
    private static void release(Minecraft mc) { releaseForwardAndJump(mc); releaseAttack(mc); }
    private static void restore(Minecraft mc, KeyMapping key) {
        // saveString is available in the Minecraft 1.21.11 mappings used here.
        // Non-keyboard bindings are released rather than leaving macro input held.
        if (mc.getWindow() == null) { key.setDown(false); return; }
        String binding = key.saveString();
        if (binding.startsWith("key.keyboard.")) {
            try {
                int code = Integer.parseInt(binding.substring("key.keyboard.".length()));
                key.setDown(InputConstants.isKeyDown(mc.getWindow(), code));
                return;
            } catch (NumberFormatException ignored) { }
        }
        key.setDown(false);
    }
}
