package top.blueicemiaow.servermanager.tools;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import top.blueicemiaow.servermanager.commands.*;

public class Register {
    public static void gamemasterCommands() {
        CommandRegistrationCallback.EVENT.register(PWD::register);
        CommandRegistrationCallback.EVENT.register(Ls::register);
        CommandRegistrationCallback.EVENT.register(CD::register);
    }

    public static void adminCommands() {
        CommandRegistrationCallback.EVENT.register(Touch::register);
        CommandRegistrationCallback.EVENT.register(MkDir::register);
    }

    public static void ownerCommands() {
        CommandRegistrationCallback.EVENT.register(Mv::register);
        CommandRegistrationCallback.EVENT.register(Rm::register);
        CommandRegistrationCallback.EVENT.register(RmDir::register);
        CommandRegistrationCallback.EVENT.register(Sh::register);
        CommandRegistrationCallback.EVENT.register(WGet::register);
        CommandRegistrationCallback.EVENT.register(TaR::register);
    }
}
