package me.alex_s168.integritycheckerserver;

import net.minecraft.resources.ResourceLocation;

public class Packets {

    public static final ResourceLocation PACKET_SERVER_REQUEST_ID =
            new ResourceLocation("integritychecker", "server_request");

    public static final ResourceLocation PACKET_CLIENT_USES_ICHECK_ID =
            new ResourceLocation("integritychecker", "client_uses_icheck");

    public static final ResourceLocation PACKET_CLIENT_SEND_ID =
            new ResourceLocation("integritychecker", "client_send");

}
