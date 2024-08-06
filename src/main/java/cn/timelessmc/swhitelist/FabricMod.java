package cn.timelessmc.swhitelist;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class FabricMod implements ModInitializer, CommandRegistrationCallback {
    public static final String MOD_ID = "swhitelist";
    public static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

    private final Gson gson = new Gson();
    private final Path whitelistPath = Path.of("mods", MOD_ID, "whitelist.json");
    private final Map<String, String> whitelist = Maps.newConcurrentMap();

    @Override
    public void onInitialize() {
        try {
            if (!Files.exists(whitelistPath)) {
                Files.createDirectories(whitelistPath.getParent());
                write();
            } else {
                JsonParser.parseReader(Files.newBufferedReader(whitelistPath))
                        .getAsJsonObject().asMap()
                        .forEach((key, value) -> whitelist.put(key, value.getAsString()));
            }

            CommandRegistrationCallback.EVENT.register(this);
            ServerPlayConnectionEvents.INIT.register((handler, server) -> {
                final var profile = handler.player.getGameProfile();
                if (whitelist.containsKey(profile.getName())) {
                    final var uuidOrEmptyString = whitelist.get(profile.getName());
                    if (uuidOrEmptyString.isEmpty()) {
                        whitelist.put(profile.getName(), profile.getId().toString());
                        LOGGER.info("Player {} is likely on his/her first time of joining the server. UUID cache has been updated.", profile.getName());
                        write();
                    } else if (!Objects.equals(whitelist.get(profile.getName()), profile.getId().toString())) {
                        handler.disconnect(Component.literal("你似乎在服务器白名单内，但 UUID 不匹配。请联系管理员解决。"));
                    }
                } else {
                    if (!whitelist.containsValue(profile.getId().toString())) {
                        handler.disconnect(Component.literal("你似乎没在服务器白名单内。请联系管理员添加。"));
                    } else {
                        whitelist.put(profile.getName(), profile.getId().toString());
                        LOGGER.info("UUID cache of {} has been updated.", profile.getName());
                        write();
                    }
                }
            });
        } catch (IOException e) {
            LOGGER.error("Failed to load whitelist: {}", e.getMessage());
        }
    }

    private void write() {
        try {
            Files.writeString(whitelistPath, gson.toJson(whitelist));
        } catch (IOException e) {
            LOGGER.error("Failed to save whitelist: {}", e.getMessage());
        }
    }

    @Override
    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        final var swhitelistCmd = literal("swhitelist")
                .requires(Permissions.require("swhitelist.whitelist", 2))
                .then(literal("+").then(argument("player", greedyString()).executes(ctx -> {
                    final var player = ctx.getArgument("player", String.class);
                    final var source = ctx.getSource();
                    if (whitelist.containsKey(player)) {
                        source.sendFailure(Component.literal("玩家已在白名单内"));
                        return 1;
                    }
                    whitelist.put(player, "");
                    source.sendSuccess(() -> Component.literal("玩家已添加到白名单"), true);
                    write();
                    return 0;
                })))
                .then(literal("-").then(argument("player", greedyString()).executes(ctx -> {
                    final var player = ctx.getArgument("player", String.class);
                    final var source = ctx.getSource();
                    if (!whitelist.containsKey(player)) {
                        source.sendFailure(Component.literal("玩家不在白名单内"));
                        return 1;
                    }
                    whitelist.remove(player);
                    source.sendSuccess(() -> Component.literal("玩家已从白名单移除"), true);
                    write();
                    return 0;
                })));
        final var swNode = dispatcher.register(swhitelistCmd);

        dispatcher.register(literal("sw").redirect(swNode));
    }
}
