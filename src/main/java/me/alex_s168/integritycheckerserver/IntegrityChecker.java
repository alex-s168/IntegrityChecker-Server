package me.alex_s168.integritycheckerserver;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class IntegrityChecker extends JavaPlugin implements Listener, PluginMessageListener, CommandExecutor {

    record PlayerInfo(
            boolean usesIcheck,
            @Nullable Integer version,
            @Nullable byte[] classesRaw,
            @Nullable Boolean parseError
    ) {}

    private Map<String, PlayerInfo> playerInfoMap = new HashMap<>();
    
    @Override
    public void onEnable() {
        PluginManager mngr = getServer().getPluginManager();
        mngr.registerEvents(this, this);

        this.getCommand("icheck").setExecutor(this);

        Messenger msgr = getServer().getMessenger();
        msgr.registerIncomingPluginChannel(this, Packets.PACKET_CLIENT_USES_ICHECK_ID.toString(), this);
        msgr.registerIncomingPluginChannel(this, Packets.PACKET_CLIENT_SEND_ID.toString(), this);
        msgr.registerOutgoingPluginChannel(this, Packets.PACKET_SERVER_REQUEST_ID.toString());
    }

    @Override
    public void onDisable() {
        
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        playerInfoMap.put(p.getName(), new PlayerInfo(false, null, null, null));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        playerInfoMap.remove(p.getName());
    }
    
    private void sendRequestPacket(ServerGamePacketListenerImpl connection, List<String> exemptClasses) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

        buf.writeVarInt(exemptClasses.size());
        for (String s : exemptClasses) {
            buf.writeVarInt(s.length());
            buf.writeCharSequence(s, StandardCharsets.UTF_8);
        }

        Packet p = new ClientboundCustomPayloadPacket(Packets.PACKET_SERVER_REQUEST_ID, buf);
        connection.send(p);
    }

    private boolean versionAllowed(int version) {
        return version == 1;
    }

    private final List<String> exemptClasses = List.of(
            "com.google.",
            "com.ibm.icu.",
            "org.apache.",
            "io.netty.",
            "org.spongepowered.",
            "net.fabricmc.",
            "org.jline.",
            "org.joml.",
            "org.lwjgl.",
            "org.ow2.",
            "org.slf4j.",
            "org.checkerframework.",
            "org.jetbrains.",
            "ca.weblite.",
            "com.github.oshi.",
            "it.unimi.",
            "org.sf.",
            "com.sun.",
            "javax.",
            "net.minecrell.",
            "net.minecraft.",
            "oshi.",
            "com.mojang.",
            "org.objectweb.",
            "joptsimple.",

            "META-INF.",
            "package-info"
    );

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (channel.equals(Packets.PACKET_CLIENT_USES_ICHECK_ID.toString())) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
            int version = buf.readVarInt();
            if (!versionAllowed(version)) {
                playerInfoMap.put(player.getName(), new PlayerInfo(true, version, null, null));
                return;
            }
            ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
            playerInfoMap.put(player.getName(), new PlayerInfo(true, version, null, null));
            sendRequestPacket(connection, exemptClasses);
        } else if (channel.equals(Packets.PACKET_CLIENT_SEND_ID.toString())) {
            PlayerInfo info = playerInfoMap.get(player.getName());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
            byte[] classes = buf.readByteArray(); // first does readVarInt and then reads that many bytes
            System.out.println("Received " + classes.length + " bytes from " + player.getName());
            playerInfoMap.put(player.getName(), new PlayerInfo(true, info.version, classes, false));
        }
    }
    
    private List<String> processClasses(Player player) {
        byte[] classesRaw = playerInfoMap.get(player.getName()).classesRaw;
        try {
            String decompressed = Compression.decompress(classesRaw);
            System.out.println(decompressed);
            String[] cd = new String[] {};
            List<String> classes = new ArrayList<>();
            assert decompressed != null;
            for (String op : decompressed.split(";")) {
                if (op.startsWith("-")) {
                    cd = Arrays.copyOfRange(cd, 0, cd.length - 1);
                } else if (op.startsWith("+")) {
                    cd = Arrays.copyOf(cd, cd.length + 1);
                    cd[cd.length - 1] = op.substring(1);
                } else {
                    classes.add(String.join(".", cd) + "." + op);
                }
            }
            return classes;
        } catch (Exception e) {
            e.printStackTrace();
            playerInfoMap.put(player.getName(), new PlayerInfo(true, playerInfoMap.get(player.getName()).version, classesRaw, true));
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("icheck")) return false;

        if (args.length < 2) {
            return false;
        }

        Player p = getServer().getPlayer(args[0]);
        if (p == null) {
            sender.sendMessage("§cPlayer not found");
            return true;
        }

        PlayerInfo info = playerInfoMap.get(p.getName());
        if (info == null) {
            sender.sendMessage("§cPlayer not found");
            return true;
        }

        if (!info.usesIcheck) {
            sender.sendMessage("§cPlayer does not use ICheck");
            return true;
        }

        if (args[1].equals("query")) {
            if (args.length < 3) {
                return false;
            }
            String clazz = args[2];
            if (info.classesRaw == null) {
                sender.sendMessage("§cPlayer has not sent their classes yet");
                return true;
            }
            List<String> classes = processClasses(p);
            if (Boolean.TRUE.equals(playerInfoMap.get(p.getName()).parseError)) {
                sender.sendMessage("§cPlayer sent invalid class map");
                return true;
            }
            if (classes == null) {
                sender.sendMessage("§cPlayer sent invalid class map");
                return true;
            }
            for (String c : classes) {
                System.out.println(c);
                if (c.startsWith(clazz)) {
                    sender.sendMessage("§a" + c);
                }
            }
            return true;
        } else if (args[1].equals("info")) {
            if (info.classesRaw == null) {
                sender.sendMessage("§cPlayer has not sent their classes yet");
            }
            if (!versionAllowed(info.version)) {
                sender.sendMessage("§cICheck version: " + info.version + " (not permitted)");
                return true;
            }
            sender.sendMessage("§aICheck version: " + info.version);
            return true;
        }
        return false;
    }
}
