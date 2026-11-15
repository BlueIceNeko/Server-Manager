package top.blueicemiaow.servermanager.tools;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import top.blueicemiaow.servermanager.commands.*;

public class Register {
    public static void commands() {
        CommandRegistrationCallback.EVENT.register(PWD::register);
    }
}
