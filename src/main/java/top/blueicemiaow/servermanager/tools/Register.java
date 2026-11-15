package top.blueicemiaow.servermanager.tools;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import top.blueicemiaow.servermanager.commands.*;

public class Register {
    public static void commands() {
        CommandRegistrationCallback.EVENT.register(PWD::register);
        CommandRegistrationCallback.EVENT.register(Ls::register);
        CommandRegistrationCallback.EVENT.register(CD::register);
        CommandRegistrationCallback.EVENT.register(Mv::register);
        CommandRegistrationCallback.EVENT.register(Touch::register);
        CommandRegistrationCallback.EVENT.register(Rm::register);
        CommandRegistrationCallback.EVENT.register(MkDir::register);
        CommandRegistrationCallback.EVENT.register(RmDir::register);
    }
}
